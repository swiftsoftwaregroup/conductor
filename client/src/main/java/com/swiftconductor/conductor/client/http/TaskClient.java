/*
 * Copyright 2023 Swift Software Group, Inc.
 * (Code and content before December 13, 2023, Copyright Netflix, Inc.)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.swiftconductor.conductor.client.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.ClientHandler;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.swiftconductor.conductor.client.config.ConductorClientConfiguration;
import com.swiftconductor.conductor.client.config.DefaultConductorClientConfiguration;
import com.swiftconductor.conductor.client.exception.ConductorClientException;
import com.swiftconductor.conductor.client.telemetry.MetricsContainer;
import com.swiftconductor.conductor.common.metadata.tasks.PollData;
import com.swiftconductor.conductor.common.metadata.tasks.Task;
import com.swiftconductor.conductor.common.metadata.tasks.TaskExecLog;
import com.swiftconductor.conductor.common.metadata.tasks.TaskResult;
import com.swiftconductor.conductor.common.run.SearchResult;
import com.swiftconductor.conductor.common.run.TaskSummary;
import com.swiftconductor.conductor.common.utils.ExternalPayloadStorage;
import com.swiftconductor.conductor.common.utils.ExternalPayloadStorage.PayloadType;

/** Client for conductor task management including polling for task, updating task status etc. */
public class TaskClient extends ClientBase {

    private static final GenericType<List<Task>> taskList = new GenericType<List<Task>>() {};

    private static final GenericType<List<TaskExecLog>> taskExecLogList =
            new GenericType<List<TaskExecLog>>() {};

    private static final GenericType<List<PollData>> pollDataList =
            new GenericType<List<PollData>>() {};

    private static final GenericType<SearchResult<TaskSummary>> searchResultTaskSummary =
            new GenericType<SearchResult<TaskSummary>>() {};

    private static final GenericType<SearchResult<Task>> searchResultTask =
            new GenericType<SearchResult<Task>>() {};

    private static final GenericType<Map<String, Integer>> queueSizeMap =
            new GenericType<Map<String, Integer>>() {};

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskClient.class);

    /** Creates a default task client */
    public TaskClient() {
        this(new DefaultClientConfig(), new DefaultConductorClientConfiguration(), null);
    }

    /**
     * @param config REST Client configuration
     */
    public TaskClient(ClientConfig config) {
        this(config, new DefaultConductorClientConfiguration(), null);
    }

    /**
     * @param config REST Client configuration
     * @param handler Jersey client handler. Useful when plugging in various http client interaction
     *     modules (e.g. ribbon)
     */
    public TaskClient(ClientConfig config, ClientHandler handler) {
        this(config, new DefaultConductorClientConfiguration(), handler);
    }

    /**
     * @param config REST Client configuration
     * @param handler Jersey client handler. Useful when plugging in various http client interaction
     *     modules (e.g. ribbon)
     * @param filters Chain of client side filters to be applied per request
     */
    public TaskClient(ClientConfig config, ClientHandler handler, ClientFilter... filters) {
        this(config, new DefaultConductorClientConfiguration(), handler, filters);
    }

    /**
     * @param config REST Client configuration
     * @param clientConfiguration Specific properties configured for the client, see {@link
     *     ConductorClientConfiguration}
     * @param handler Jersey client handler. Useful when plugging in various http client interaction
     *     modules (e.g. ribbon)
     * @param filters Chain of client side filters to be applied per request
     */
    public TaskClient(
            ClientConfig config,
            ConductorClientConfiguration clientConfiguration,
            ClientHandler handler,
            ClientFilter... filters) {
        super(new ClientRequestHandler(config, handler, filters), clientConfiguration);
    }

    TaskClient(ClientRequestHandler requestHandler) {
        super(requestHandler, null);
    }

    /**
     * Perform a poll for a task of a specific task type.
     *
     * @param taskType The taskType to poll for
     * @param domain The domain of the task type
     * @param workerId Name of the client worker. Used for logging.
     * @return Task waiting to be executed.
     */
    public Task pollTask(String taskType, String workerId, String domain) {
        Validate.notBlank(taskType, "Task type cannot be blank");
        Validate.notBlank(workerId, "Worker id cannot be blank");

        Object[] params = new Object[] {"workerid", workerId, "domain", domain};
        Task task =
                ObjectUtils.defaultIfNull(
                        getForEntity("tasks/poll/{taskType}", params, Task.class, taskType),
                        new Task());
        populateTaskPayloads(task);
        return task;
    }

    /**
     * Perform a batch poll for tasks by task type. Batch size is configurable by count.
     *
     * @param taskType Type of task to poll for
     * @param workerId Name of the client worker. Used for logging.
     * @param count Maximum number of tasks to be returned. Actual number of tasks returned can be
     *     less than this number.
     * @param timeoutInMillisecond Long poll wait timeout.
     * @return List of tasks awaiting to be executed.
     */
    public List<Task> batchPollTasksByTaskType(
            String taskType, String workerId, int count, int timeoutInMillisecond) {
        Validate.notBlank(taskType, "Task type cannot be blank");
        Validate.notBlank(workerId, "Worker id cannot be blank");
        Validate.isTrue(count > 0, "Count must be greater than 0");

        Object[] params =
                new Object[] {
                    "workerid", workerId, "count", count, "timeout", timeoutInMillisecond
                };
        List<Task> tasks = getForEntity("tasks/poll/batch/{taskType}", params, taskList, taskType);
        tasks.forEach(this::populateTaskPayloads);
        return tasks;
    }

    /**
     * Batch poll for tasks in a domain. Batch size is configurable by count.
     *
     * @param taskType Type of task to poll for
     * @param domain The domain of the task type
     * @param workerId Name of the client worker. Used for logging.
     * @param count Maximum number of tasks to be returned. Actual number of tasks returned can be
     *     less than this number.
     * @param timeoutInMillisecond Long poll wait timeout.
     * @return List of tasks awaiting to be executed.
     */
    public List<Task> batchPollTasksInDomain(
            String taskType, String domain, String workerId, int count, int timeoutInMillisecond) {
        Validate.notBlank(taskType, "Task type cannot be blank");
        Validate.notBlank(workerId, "Worker id cannot be blank");
        Validate.isTrue(count > 0, "Count must be greater than 0");

        Object[] params =
                new Object[] {
                    "workerid",
                    workerId,
                    "count",
                    count,
                    "timeout",
                    timeoutInMillisecond,
                    "domain",
                    domain
                };
        List<Task> tasks = getForEntity("tasks/poll/batch/{taskType}", params, taskList, taskType);
        tasks.forEach(this::populateTaskPayloads);
        return tasks;
    }

    /**
     * Populates the task input/output from external payload storage if the external storage path is
     * specified.
     *
     * @param task the task for which the input is to be populated.
     */
    private void populateTaskPayloads(Task task) {
        if (StringUtils.isNotBlank(task.getExternalInputPayloadStoragePath())) {
            MetricsContainer.incrementExternalPayloadUsedCount(
                    task.getTaskDefName(),
                    ExternalPayloadStorage.Operation.READ.name(),
                    ExternalPayloadStorage.PayloadType.TASK_INPUT.name());
            task.setInputData(
                    downloadFromExternalStorage(
                            ExternalPayloadStorage.PayloadType.TASK_INPUT,
                            task.getExternalInputPayloadStoragePath()));
            task.setExternalInputPayloadStoragePath(null);
        }
        if (StringUtils.isNotBlank(task.getExternalOutputPayloadStoragePath())) {
            MetricsContainer.incrementExternalPayloadUsedCount(
                    task.getTaskDefName(),
                    ExternalPayloadStorage.Operation.READ.name(),
                    PayloadType.TASK_OUTPUT.name());
            task.setOutputData(
                    downloadFromExternalStorage(
                            ExternalPayloadStorage.PayloadType.TASK_OUTPUT,
                            task.getExternalOutputPayloadStoragePath()));
            task.setExternalOutputPayloadStoragePath(null);
        }
    }

    /**
     * Updates the result of a task execution. If the size of the task output payload is bigger than
     * {@link ConductorClientConfiguration#getTaskOutputPayloadThresholdKB()}, it is uploaded to
     * {@link ExternalPayloadStorage}, if enabled, else the task is marked as
     * FAILED_WITH_TERMINAL_ERROR.
     *
     * @param taskResult the {@link TaskResult} of the executed task to be updated.
     */
    public void updateTask(TaskResult taskResult) {
        Validate.notNull(taskResult, "Task result cannot be null");
        postForEntityWithRequestOnly("tasks", taskResult);
    }

    public Optional<String> evaluateAndUploadLargePayload(
            Map<String, Object> taskOutputData, String taskType) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            objectMapper.writeValue(byteArrayOutputStream, taskOutputData);
            byte[] taskOutputBytes = byteArrayOutputStream.toByteArray();
            long taskResultSize = taskOutputBytes.length;
            MetricsContainer.recordTaskResultPayloadSize(taskType, taskResultSize);

            long payloadSizeThreshold =
                    conductorClientConfiguration.getTaskOutputPayloadThresholdKB() * 1024L;
            if (taskResultSize > payloadSizeThreshold) {
                if (!conductorClientConfiguration.isExternalPayloadStorageEnabled()
                        || taskResultSize
                                > conductorClientConfiguration.getTaskOutputMaxPayloadThresholdKB()
                                        * 1024L) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "The TaskResult payload size: %d is greater than the permissible %d bytes",
                                    taskResultSize, payloadSizeThreshold));
                }
                MetricsContainer.incrementExternalPayloadUsedCount(
                        taskType,
                        ExternalPayloadStorage.Operation.WRITE.name(),
                        ExternalPayloadStorage.PayloadType.TASK_OUTPUT.name());
                return Optional.of(
                        uploadToExternalPayloadStorage(
                                PayloadType.TASK_OUTPUT, taskOutputBytes, taskResultSize));
            }
            return Optional.empty();
        } catch (IOException e) {
            String errorMsg = String.format("Unable to update task: %s with task result", taskType);
            LOGGER.error(errorMsg, e);
            throw new ConductorClientException(errorMsg, e);
        }
    }

    /**
     * Ack for the task poll.
     *
     * @param taskId Id of the task to be polled
     * @param workerId user identified worker.
     * @return true if the task was found with the given ID and acknowledged. False otherwise. If
     *     the server returns false, the client should NOT attempt to ack again.
     */
    public Boolean ack(String taskId, String workerId) {
        Validate.notBlank(taskId, "Task id cannot be blank");

        String response =
                postForEntity(
                        "tasks/{taskId}/ack",
                        null,
                        new Object[] {"workerid", workerId},
                        String.class,
                        taskId);
        return Boolean.valueOf(response);
    }

    /**
     * Log execution messages for a task.
     *
     * @param taskId id of the task
     * @param logMessage the message to be logged
     */
    public void logMessageForTask(String taskId, String logMessage) {
        Validate.notBlank(taskId, "Task id cannot be blank");
        postForEntityWithRequestOnly("tasks/" + taskId + "/log", logMessage);
    }

    /**
     * Fetch execution logs for a task.
     *
     * @param taskId id of the task.
     */
    public List<TaskExecLog> getTaskLogs(String taskId) {
        Validate.notBlank(taskId, "Task id cannot be blank");
        return getForEntity("tasks/{taskId}/log", null, taskExecLogList, taskId);
    }

    /**
     * Retrieve information about the task
     *
     * @param taskId ID of the task
     * @return Task details
     */
    public Task getTaskDetails(String taskId) {
        Validate.notBlank(taskId, "Task id cannot be blank");
        return getForEntity("tasks/{taskId}", null, Task.class, taskId);
    }

    /**
     * Removes a task from a taskType queue
     *
     * @param taskType the taskType to identify the queue
     * @param taskId the id of the task to be removed
     */
    public void removeTaskFromQueue(String taskType, String taskId) {
        Validate.notBlank(taskType, "Task type cannot be blank");
        Validate.notBlank(taskId, "Task id cannot be blank");

        delete("tasks/queue/{taskType}/{taskId}", taskType, taskId);
    }

    public int getQueueSizeForTask(String taskType) {
        Validate.notBlank(taskType, "Task type cannot be blank");

        Integer queueSize =
                getForEntity(
                        "tasks/queue/size",
                        new Object[] {"taskType", taskType},
                        new GenericType<Integer>() {});
        return queueSize != null ? queueSize : 0;
    }

    public int getQueueSizeForTask(
            String taskType, String domain, String isolationGroupId, String executionNamespace) {
        Validate.notBlank(taskType, "Task type cannot be blank");

        List<Object> params = new LinkedList<>();
        params.add("taskType");
        params.add(taskType);

        if (StringUtils.isNotBlank(domain)) {
            params.add("domain");
            params.add(domain);
        }

        if (StringUtils.isNotBlank(isolationGroupId)) {
            params.add("isolationGroupId");
            params.add(isolationGroupId);
        }

        if (StringUtils.isNotBlank(executionNamespace)) {
            params.add("executionNamespace");
            params.add(executionNamespace);
        }

        Integer queueSize =
                getForEntity(
                        "tasks/queue/size",
                        params.toArray(new Object[0]),
                        new GenericType<Integer>() {});
        return queueSize != null ? queueSize : 0;
    }

    /**
     * Get last poll data for a given task type
     *
     * @param taskType the task type for which poll data is to be fetched
     * @return returns the list of poll data for the task type
     */
    public List<PollData> getPollData(String taskType) {
        Validate.notBlank(taskType, "Task type cannot be blank");

        Object[] params = new Object[] {"taskType", taskType};
        return getForEntity("tasks/queue/polldata", params, pollDataList);
    }

    /**
     * Get the last poll data for all task types
     *
     * @return returns a list of poll data for all task types
     */
    public List<PollData> getAllPollData() {
        return getForEntity("tasks/queue/polldata/all", null, pollDataList);
    }

    /**
     * Requeue pending tasks for all running workflows
     *
     * @return returns the number of tasks that have been requeued
     */
    public String requeueAllPendingTasks() {
        return postForEntity("tasks/queue/requeue", null, null, String.class);
    }

    /**
     * Requeue pending tasks of a specific task type
     *
     * @return returns the number of tasks that have been requeued
     */
    public String requeuePendingTasksByTaskType(String taskType) {
        Validate.notBlank(taskType, "Task type cannot be blank");
        return postForEntity("tasks/queue/requeue/{taskType}", null, null, String.class, taskType);
    }

    /**
     * Search for tasks based on payload
     *
     * @param query the search string
     * @return returns the {@link SearchResult} containing the {@link TaskSummary} matching the
     *     query
     */
    public SearchResult<TaskSummary> search(String query) {
        return getForEntity("tasks/search", new Object[] {"query", query}, searchResultTaskSummary);
    }

    /**
     * Search for tasks based on payload
     *
     * @param query the search string
     * @return returns the {@link SearchResult} containing the {@link Task} matching the query
     */
    public SearchResult<Task> searchV2(String query) {
        return getForEntity("tasks/search-v2", new Object[] {"query", query}, searchResultTask);
    }

    /**
     * Paginated search for tasks based on payload
     *
     * @param start start value of page
     * @param size number of tasks to be returned
     * @param sort sort order
     * @param freeText additional free text query
     * @param query the search query
     * @return the {@link SearchResult} containing the {@link TaskSummary} that match the query
     */
    public SearchResult<TaskSummary> search(
            Integer start, Integer size, String sort, String freeText, String query) {
        Object[] params =
                new Object[] {
                    "start", start, "size", size, "sort", sort, "freeText", freeText, "query", query
                };
        return getForEntity("tasks/search", params, searchResultTaskSummary);
    }

    /**
     * Paginated search for tasks based on payload
     *
     * @param start start value of page
     * @param size number of tasks to be returned
     * @param sort sort order
     * @param freeText additional free text query
     * @param query the search query
     * @return the {@link SearchResult} containing the {@link Task} that match the query
     */
    public SearchResult<Task> searchV2(
            Integer start, Integer size, String sort, String freeText, String query) {
        Object[] params =
                new Object[] {
                    "start", start, "size", size, "sort", sort, "freeText", freeText, "query", query
                };
        return getForEntity("tasks/search-v2", params, searchResultTask);
    }
}
