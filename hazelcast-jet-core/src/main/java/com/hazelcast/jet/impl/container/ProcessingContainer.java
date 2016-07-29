/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.container;

import com.hazelcast.jet.dag.Vertex;
import com.hazelcast.jet.dag.sink.Sink;
import com.hazelcast.jet.dag.source.Source;
import com.hazelcast.jet.data.DataWriter;
import com.hazelcast.jet.impl.actor.ObjectProducer;
import com.hazelcast.jet.impl.container.events.DefaultEventProcessorFactory;
import com.hazelcast.jet.impl.container.events.EventProcessorFactory;
import com.hazelcast.jet.impl.container.processingcontainer.ProcessingContainerEvent;
import com.hazelcast.jet.impl.container.processingcontainer.ProcessingContainerResponse;
import com.hazelcast.jet.impl.container.processingcontainer.ProcessingContainerState;
import com.hazelcast.jet.impl.container.task.DefaultContainerTask;
import com.hazelcast.jet.impl.container.task.DefaultTaskContext;
import com.hazelcast.jet.impl.container.task.TaskEvent;
import com.hazelcast.jet.impl.container.task.TaskProcessorFactory;
import com.hazelcast.jet.impl.container.task.processors.factory.DefaultTaskProcessorFactory;
import com.hazelcast.jet.impl.container.task.processors.factory.ShuffledTaskProcessorFactory;
import com.hazelcast.jet.impl.job.JobContext;
import com.hazelcast.jet.impl.statemachine.StateMachine;
import com.hazelcast.jet.impl.statemachine.StateMachineFactory;
import com.hazelcast.jet.impl.statemachine.container.ProcessingContainerStateMachine;
import com.hazelcast.jet.impl.statemachine.container.processors.ContainerPayloadFactory;
import com.hazelcast.jet.impl.statemachine.container.requests.ContainerFinalizedRequest;
import com.hazelcast.jet.processor.ContainerProcessor;
import com.hazelcast.spi.NodeEngine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;

@SuppressFBWarnings("EI_EXPOSE_REP")
public class ProcessingContainer extends
        AbstractContainer<ProcessingContainerEvent, ProcessingContainerState, ProcessingContainerResponse> {

    private static final StateMachineFactory<ProcessingContainerEvent,
            StateMachine<ProcessingContainerEvent, ProcessingContainerState, ProcessingContainerResponse>>
            STATE_MACHINE_FACTORY = ProcessingContainerStateMachine::new;

    private final Vertex vertex;

    private final Map<Integer, ContainerTask> containerTasksCache = new ConcurrentHashMap<>();

    private final int tasksCount;

    private final int awaitSecondsTimeOut;

    private final ContainerTask[] containerTasks;

    private final List<DataChannel> inputChannels;

    private final List<DataChannel> outputChannels;

    private final List<ObjectProducer> sourcesProducers;

    private final TaskProcessorFactory taskProcessorFactory;

    private final EventProcessorFactory eventProcessorFactory;

    private final Supplier<ContainerProcessor> containerProcessorFactory;

    private final AtomicInteger taskIdGenerator = new AtomicInteger(0);

    public ProcessingContainer(
            Vertex vertex, Supplier<ContainerProcessor> containerProcessorFactory, NodeEngine nodeEngine,
            JobContext jobContext
    ) {
        super(vertex, STATE_MACHINE_FACTORY, nodeEngine, jobContext);
        this.vertex = vertex;
        this.inputChannels = new ArrayList<>();
        this.outputChannels = new ArrayList<>();
        this.taskProcessorFactory = isShuffled(vertex, nodeEngine) ? new ShuffledTaskProcessorFactory()
                : new DefaultTaskProcessorFactory();
        this.tasksCount = vertex.getDescriptor().getTaskCount();
        this.sourcesProducers = new ArrayList<>();
        this.containerProcessorFactory = containerProcessorFactory;
        this.containerTasks = new ContainerTask[this.tasksCount];
        this.awaitSecondsTimeOut = getJobContext().getJobConfig().getSecondsToAwait();
        AtomicInteger readyForFinalizationTasksCounter = new AtomicInteger(0);
        readyForFinalizationTasksCounter.set(this.tasksCount);
        AtomicInteger completedTasks = new AtomicInteger(0);
        AtomicInteger interruptedTasks = new AtomicInteger(0);
        this.eventProcessorFactory = new DefaultEventProcessorFactory(completedTasks, interruptedTasks,
                readyForFinalizationTasksCounter, this.containerTasks, this.getContainerContext(), this);
        buildTasks();
        buildSources();
        buildSinks();
    }

    private void buildTasks() {
        ContainerTask[] containerTasks = getContainerTasks();
        for (int taskIndex = 0; taskIndex < this.tasksCount; taskIndex++) {
            int taskID = taskIdGenerator.incrementAndGet();
            containerTasks[taskIndex] = new DefaultContainerTask(this, getVertex(), taskProcessorFactory,
                    taskID, new DefaultTaskContext(tasksCount, taskIndex, getJobContext()));
            getJobContext().getExecutorContext().getProcessingTasks().add(containerTasks[taskIndex]);
            containerTasks[taskIndex].setThreadContextClassLoaders(
                    getJobContext().getLocalizationStorage().getClassLoader());
            containerTasksCache.put(taskID, containerTasks[taskIndex]);
        }
    }

    /**
     * Handles task's event;
     *
     * @param containerTask - corresponding container task;
     * @param event         - task's event;
     */
    public void handleTaskEvent(ContainerTask containerTask, TaskEvent event) {
        handleTaskEvent(containerTask, event, null);
    }

    /**
     * Handles task's error-event;
     *
     * @param containerTask - corresponding container task;
     * @param event         - task's event;
     * @param error         - corresponding error;
     */
    public void handleTaskEvent(ContainerTask containerTask, TaskEvent event, Throwable error) {
        this.eventProcessorFactory.getEventProcessor(event).process(containerTask, event, error);
    }

    /**
     * @return - user-level container processing factory;
     */
    public final Supplier<ContainerProcessor> getContainerProcessorFactory() {
        return containerProcessorFactory;
    }

    /**
     * @return - tasks of the container;
     */
    public ContainerTask[] getContainerTasks() {
        return containerTasks;
    }

    /**
     * TaskID -&gt; ContainerTask;
     *
     * @return - cache of container's tasks;
     */
    public Map<Integer, ContainerTask> getTasksCache() {
        return containerTasksCache;
    }

    /**
     * @return - vertex for the corresponding container;
     */
    public Vertex getVertex() {
        return vertex;
    }

    /**
     * @return - list of the input channels;
     */
    public List<DataChannel> getInputChannels() {
        return inputChannels;
    }

    /**
     * @return - list of the output channels;
     */
    public List<DataChannel> getOutputChannels() {
        return outputChannels;
    }

    /**
     * Adds input channel for container.
     */
    public void addInputChannel(DataChannel channel) {
        inputChannels.add(channel);
    }

    /**
     * Adds output channel for container.
     */
    public void addOutputChannel(DataChannel channel) {
        outputChannels.add(channel);
    }

    /**
     * Starts execution of containers
     */
    public void start() {
        int taskCount = getContainerTasks().length;
        if (taskCount == 0) {
            throw new IllegalStateException("No containerTasks for container");
        }
        List<ObjectProducer> producers = new ArrayList<>(sourcesProducers);
        List<ObjectProducer>[] tasksProducers = new List[taskCount];
        for (int taskIdx = 0; taskIdx < getContainerTasks().length; taskIdx++) {
            tasksProducers[taskIdx] = new ArrayList<>();
        }
        int taskId = 0;
        for (ObjectProducer producer : producers) {
            tasksProducers[taskId].add(producer);
            taskId = (taskId + 1) % taskCount;
        }
        for (int taskIdx = 0; taskIdx < getContainerTasks().length; taskIdx++) {
            startTask(tasksProducers[taskIdx], taskIdx);
        }
    }

    private void startTask(List<ObjectProducer> producers, int taskIdx) {
        for (DataChannel channel : getInputChannels()) {
            producers.addAll(channel.getActors()
                    .stream()
                    .map(actor -> actor.getParties()[taskIdx])
                    .collect(toList()));
        }
        getContainerTasks()[taskIdx].start(producers);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void processRequest(ProcessingContainerEvent event, Object payload) throws Exception {
        ContainerPayloadProcessor processor = ContainerPayloadFactory.getProcessor(event, this);
        if (processor != null) {
            processor.process(payload);
        }
    }

    /**
     * Destroys the containers.
     */
    public void destroy() throws Exception {
        handleContainerRequest(new ContainerFinalizedRequest(this)).get(this.awaitSecondsTimeOut, TimeUnit.SECONDS);
    }

    /**
     * Interrupts the execution of containers.
     *
     * @param error the error that's causing the interruption
     */
    public void interrupt(Throwable error) {
        for (ContainerTask task : containerTasks) {
            task.interrupt(error);
        }
    }

    @Override
    protected void wakeUpExecutor() {
        getJobContext().getExecutorContext().getDataContainerStateMachineExecutor().wakeUp();
    }

    private boolean isShuffled(Vertex vertex, NodeEngine nodeEngine) {
        return (hasOutputShuffler(vertex) && clusterHasMultipleMembers(nodeEngine)) || hasSink(vertex);
    }

    private boolean clusterHasMultipleMembers(NodeEngine nodeEngine) {
        return nodeEngine.getClusterService().getMembers().size() > 1;
    }

    private boolean hasOutputShuffler(Vertex vertex) {
        return vertex.hasOutputShuffler();
    }

    private boolean hasSink(Vertex vertex) {
        return vertex.getSinks().size() > 0;
    }

    private void buildSinks() {
        List<Sink> sinks = getVertex().getSinks();
        for (Sink sink : sinks) {
            if (sink.isPartitioned()) {
                for (ContainerTask containerTask : getContainerTasks()) {
                    List<DataWriter> writers = getDataWriters(sink);
                    containerTask.registerSinkWriters(writers);
                }
            } else {
                List<DataWriter> writers = getDataWriters(sink);
                int i = 0;

                for (ContainerTask containerTask : getContainerTasks()) {
                    List<DataWriter> sinkWriters = new ArrayList<>(sinks.size());
                    if (writers.size() >= i - 1) {
                        sinkWriters.add(writers.get(i++));
                        containerTask.registerSinkWriters(sinkWriters);
                    } else {
                        break;
                    }
                }
            }
        }
    }

    private void buildSources() {
        if (getVertex().getSources().size() > 0) {
            for (Source source : getVertex().getSources()) {
                List<ObjectProducer> readers = getDataReaders(source);
                sourcesProducers.addAll(readers);
            }
        }
    }

    private List<ObjectProducer> getDataReaders(Source source) {
        return Arrays.asList(source.getReaders(getContainerContext(), getVertex()));
    }

    private List<DataWriter> getDataWriters(Sink sink) {
        return Arrays.asList(sink.getWriters(getNodeEngine(), getContainerContext()));
    }
}