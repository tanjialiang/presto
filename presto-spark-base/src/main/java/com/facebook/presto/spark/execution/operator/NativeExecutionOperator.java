/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark.execution.operator;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.common.Page;
import com.facebook.presto.common.block.BlockEncodingSerde;
import com.facebook.presto.execution.ScheduledSplit;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.execution.TaskSource;
import com.facebook.presto.execution.buffer.PagesSerdeFactory;
import com.facebook.presto.execution.scheduler.TableWriteInfo;
import com.facebook.presto.memory.context.LocalMemoryContext;
import com.facebook.presto.operator.DriverContext;
import com.facebook.presto.operator.OperatorContext;
import com.facebook.presto.operator.OperatorFactory;
import com.facebook.presto.operator.SourceOperator;
import com.facebook.presto.operator.SourceOperatorFactory;
import com.facebook.presto.spark.execution.NativeExecutionProcess;
import com.facebook.presto.spark.execution.NativeExecutionProcessFactory;
import com.facebook.presto.spark.execution.NativeExecutionTask;
import com.facebook.presto.spark.execution.NativeExecutionTaskFactory;
import com.facebook.presto.spi.UpdatablePageSource;
import com.facebook.presto.spi.page.PagesSerde;
import com.facebook.presto.spi.page.SerializedPage;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.sql.planner.LocalExecutionPlanner;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.plan.InternalPlanVisitor;
import com.facebook.presto.sql.planner.plan.NativeExecutionNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static com.facebook.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static com.facebook.presto.SystemSessionProperties.isExchangeChecksumEnabled;
import static com.facebook.presto.SystemSessionProperties.isExchangeCompressionEnabled;
import static com.facebook.presto.operator.PipelineExecutionStrategy.UNGROUPED_EXECUTION;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * NativeExecutionOperator is responsible for launching the external native process and managing the communication
 * between Java process and native process (by using the {@Link NativeExecutionTask}). The NativeExecutionOperator will send  necessary meta information
 * (e.g, plan fragment, session properties etc.) will be sent to native process and collect the execution results (data, metrics etc) back and propagate out as
 * the operator output through the operator's getOutput method.
 * The lifecycle of the NativeExecutionOperator is:
 * 1. Launch the native engine external process when initializing the operator.
 * 2. Serialize and pass the planFragment, tableWriteInfo and taskSource to the external process through {@link NativeExecutionTask} APIs.
 * 3. Call {@link NativeExecutionTask}'s pollResult() to retrieve {@link SerializedPage} back from external process.
 * 4. Deserialize {@link SerializedPage} to {@link Page} and return it back to driver from the getOutput method.
 * 5. The close() will be called by the driver when {@link NativeExecutionTask} completes and pollResult() returns an empty result.
 * 6. Shut down the external process upon calling of close() method
 * <p>
 */
public class NativeExecutionOperator
        implements SourceOperator
{
    private static final Logger log = Logger.get(NativeExecutionOperator.class);
    private static final String NATIVE_EXECUTION_SERVER_URI = "http://127.0.0.1";

    private final PlanNodeId sourceId;
    private final OperatorContext operatorContext;
    private final LocalMemoryContext systemMemoryContext;
    private final PlanFragment planFragment;
    private final TableWriteInfo tableWriteInfo;
    private final Optional<String> shuffleWriteInfo;
    private final PagesSerde serde;
    private final NativeExecutionProcessFactory processFactory;
    private final NativeExecutionTaskFactory taskFactory;

    private NativeExecutionProcess process;
    private NativeExecutionTask task;
    private CompletableFuture<TaskInfo> taskStatusFuture;
    private ImmutableSet.Builder<ScheduledSplit> splitSetBuilder;
    private TaskSource taskSource;
    private PlanNodeId nativePlanNodeId;
    private boolean finished;
    private boolean noMoreSplits;

    public NativeExecutionOperator(
            PlanNodeId sourceId,
            OperatorContext operatorContext,
            PlanFragment planFragment,
            TableWriteInfo tableWriteInfo,
            PagesSerde serde,
            NativeExecutionProcessFactory processFactory,
            NativeExecutionTaskFactory taskFactory,
            Optional<String> shuffleWriteInfo)
    {
        this.sourceId = requireNonNull(sourceId, "sourceId is null");
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.systemMemoryContext = operatorContext.localSystemMemoryContext();
        this.planFragment = requireNonNull(planFragment, "planFragment is null");
        this.tableWriteInfo = requireNonNull(tableWriteInfo, "tableWriteInfo is null");
        this.shuffleWriteInfo = requireNonNull(shuffleWriteInfo, "shuffleWriteInfo is null");
        this.serde = requireNonNull(serde, "serde is null");
        this.processFactory = requireNonNull(processFactory, "processFactory is null");
        this.taskFactory = requireNonNull(taskFactory, "taskFactory is null");
        this.splitSetBuilder = ImmutableSet.builder();
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public boolean needsInput()
    {
        return false;
    }

    @Override
    public void addInput(Page page)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * The overall workflow of the getOutput method is:
     * 1. Submit the plan to the external process
     * 2. Call pollResult method to get latest buffered result.
     * 3. Call getTaskInfo method to get the TaskInfo and propagate it
     * 4. Deserialize the polled {@link SerializedPage} to {@link Page} and return it back
     */
    @Override
    public Page getOutput()
    {
        log.info("getOutput()");
        if (!noMoreSplits) {
            return null;
        }

        if (process == null) {
            log.info("starting process");
            createProcess();
            checkState(process != null, "process is null");
            createTask();
            checkState(task != null, "task is null");
            taskStatusFuture = task.start();
        }

        try {
            if (taskStatusFuture.isDone()) {
                log.info("taskStatusFuture.isDone");
                // Will throw exception if the  taskStatusFuture is done with error.
                TaskInfo taskInfo = taskStatusFuture.get();
                taskInfo.getTaskStatus().getFailures().forEach(e -> log.error(e.toException()));

                // Consume the left over results buffered locally in HttpNativeExecutionTaskResultFetcher if any.
                Optional<SerializedPage> page = task.pollResult();
                if (page.isPresent()) {
                    log.info("pagePresent " + page.get().getSizeInBytes() + " bytes");
                    return processResult(page.get());
                }
                else {
                    log.info("finished");
                    finished = true;
                    return null;
                }
            }

            Optional<SerializedPage> page = task.pollResult();
            return page.map(this::processResult).orElse(null);
        }
        catch (InterruptedException | ExecutionException e) {
            log.error(e);
            throw new RuntimeException(e);
        }
    }

    private void createProcess()
    {
        try {
            this.process = processFactory.createNativeExecutionProcess(
                    operatorContext.getSession(),
                    URI.create(NATIVE_EXECUTION_SERVER_URI));
            log.info("Starting native execution process of task" + getOperatorContext().getDriverContext().getTaskId().toString());
            process.start();
        }
        catch (ExecutionException | InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createTask()
    {
        checkState(taskSource != null, "taskSource is null");
        checkState(taskStatusFuture == null, "taskStatusFuture has already been set");
        checkState(task == null, "task has already been set");
        checkState(process != null, "process is null");
        this.task = taskFactory.createNativeExecutionTask(
                operatorContext.getSession(),
                uriBuilderFrom(URI.create(NATIVE_EXECUTION_SERVER_URI)).port(process.getPort()).build(),
                operatorContext.getDriverContext().getTaskId(),
                planFragment,
                ImmutableList.of(taskSource),
                tableWriteInfo,
                shuffleWriteInfo);
    }

    private Page processResult(SerializedPage page)
    {
        operatorContext.recordRawInput(page.getSizeInBytes(), page.getPositionCount());
        Page deserializedPage = serde.deserialize(page);
        operatorContext.recordProcessedInput(deserializedPage.getSizeInBytes(), page.getPositionCount());
        return deserializedPage;
    }

    @Override
    public void finish()
    {
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }

    @Override
    public PlanNodeId getSourceId()
    {
        return sourceId;
    }

    @Override
    public Supplier<Optional<UpdatablePageSource>> addSplit(ScheduledSplit split)
    {
        requireNonNull(split, "split is null");
        if (nativePlanNodeId == null) {
            nativePlanNodeId = split.getPlanNodeId();
        }
        else {
            checkArgument(nativePlanNodeId == split.getPlanNodeId());
        }
        splitSetBuilder.add(split);
        return Optional::empty;
    }

    @Override
    public void noMoreSplits()
    {
        log.info("noMoreSplits()");
        noMoreSplits = true;
        this.taskSource = new TaskSource(nativePlanNodeId, splitSetBuilder.build(), true);
    }

    @Override
    public void close()
    {
        systemMemoryContext.setBytes(0);
        if (task != null) {
            task.stop();
        }
        if (process != null) {
            log.info("Closing native execution process for task " + getOperatorContext().getDriverContext().getTaskId().toString());
            process.close();
        }
    }

    public static class NativeExecutionOperatorFactory
            implements SourceOperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final PlanFragment planFragment;
        private final TableWriteInfo tableWriteInfo;
        private final Optional<String> shuffleWriteInfo;
        private final PagesSerdeFactory serdeFactory;
        private final NativeExecutionProcessFactory processFactory;
        private final NativeExecutionTaskFactory taskFactory;
        private boolean closed;

        public NativeExecutionOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                PlanFragment planFragment,
                TableWriteInfo tableWriteInfo,
                PagesSerdeFactory serdeFactory,
                NativeExecutionProcessFactory processFactory,
                NativeExecutionTaskFactory taskFactory,
                Optional<String> shuffleWriteInfo)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.planFragment = requireNonNull(planFragment, "planFragment is null");
            this.tableWriteInfo = requireNonNull(tableWriteInfo, "tableWriteInfo is null");
            this.shuffleWriteInfo = requireNonNull(shuffleWriteInfo, "shuffleWriteInfo is null");
            this.serdeFactory = requireNonNull(serdeFactory, "serdeFactory is null");
            this.processFactory = requireNonNull(processFactory, "processFactory is null");
            this.taskFactory = requireNonNull(taskFactory, "taskFactory is null");
        }

        @Override
        public PlanNodeId getSourceId()
        {
            return planNodeId;
        }

        @Override
        public SourceOperator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "operator factory is closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, NativeExecutionOperator.class.getSimpleName());
            return new NativeExecutionOperator(
                    planNodeId,
                    operatorContext,
                    planFragment,
                    tableWriteInfo,
                    serdeFactory.createPagesSerde(),
                    processFactory,
                    taskFactory,
                    shuffleWriteInfo);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        public PlanFragment getPlanFragment()
        {
            return planFragment;
        }
    }

    public static class NativeExecutionOperatorTranslator
            extends LocalExecutionPlanner.CustomPlanTranslator
    {
        private final PlanFragment fragment;
        private final Session session;
        private final Optional<String> shuffleWriteInfo;
        private final BlockEncodingSerde blockEncodingSerde;
        private final NativeExecutionProcessFactory processFactory;
        private final NativeExecutionTaskFactory taskFactory;

        public NativeExecutionOperatorTranslator(
                Session session,
                PlanFragment fragment,
                BlockEncodingSerde blockEncodingSerde,
                NativeExecutionProcessFactory processFactory,
                NativeExecutionTaskFactory taskFactory,
                Optional<String> shuffleWriteInfo)
        {
            this.fragment = requireNonNull(fragment, "fragment is null");
            this.session = requireNonNull(session, "session is null");
            this.shuffleWriteInfo = requireNonNull(shuffleWriteInfo, "shuffleWriteInfo is null");
            this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
            this.processFactory = requireNonNull(processFactory, "processFactory is null");
            this.taskFactory = requireNonNull(taskFactory, "taskFactory is null");
        }

        @Override
        public Optional<LocalExecutionPlanner.PhysicalOperation> translate(
                PlanNode node,
                LocalExecutionPlanner.LocalExecutionPlanContext context,
                InternalPlanVisitor<LocalExecutionPlanner.PhysicalOperation, LocalExecutionPlanner.LocalExecutionPlanContext> visitor)
        {
            if (node instanceof NativeExecutionNode) {
                OperatorFactory operatorFactory = new NativeExecutionOperator.NativeExecutionOperatorFactory(
                        context.getNextOperatorId(),
                        node.getId(),
                        fragment.withSubPlan(((NativeExecutionNode) node).getSubPlan()),
                        context.getTableWriteInfo(),
                        new PagesSerdeFactory(blockEncodingSerde, isExchangeCompressionEnabled(session), isExchangeChecksumEnabled(session)),
                        processFactory,
                        taskFactory,
                        shuffleWriteInfo);
                return Optional.of(
                        new LocalExecutionPlanner.PhysicalOperation(operatorFactory, makeLayout(node), context, UNGROUPED_EXECUTION));
            }
            return Optional.empty();
        }
    }
}
