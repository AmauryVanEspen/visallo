package org.visallo.vertexium.model.longRunningProcess;

import com.google.inject.Inject;
import org.visallo.core.model.longRunningProcess.LongRunningProcessProperties;
import org.visallo.core.model.longRunningProcess.LongRunningProcessRepository;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.user.User;
import org.json.JSONObject;
import org.vertexium.*;
import org.vertexium.util.ConvertingIterable;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.vertexium.util.IterableUtils.toList;

public class VertexiumLongRunningProcessRepository extends LongRunningProcessRepository {
    private final WorkQueueRepository workQueueRepository;
    private final UserRepository userRepository;
    private final Graph graph;

    @Inject
    public VertexiumLongRunningProcessRepository(
            AuthorizationRepository authorizationRepository,
            UserRepository userRepository,
            WorkQueueRepository workQueueRepository,
            Graph graph) {
        this.userRepository = userRepository;
        this.workQueueRepository = workQueueRepository;
        this.graph = graph;

        authorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
    }

    @Override
    public String enqueue(JSONObject longRunningProcessQueueItem, User user, Authorizations authorizations) {
        authorizations = getAuthorizations(user);

        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user with id: " + user.getUserId());
        Visibility visibility = getVisibility();

        VertexBuilder vertexBuilder = this.graph.prepareVertex(visibility);
        VisalloProperties.CONCEPT_TYPE.setProperty(vertexBuilder, LongRunningProcessProperties.LONG_RUNNING_PROCESS_CONCEPT_IRI, visibility);
        longRunningProcessQueueItem.put("enqueueTime", System.currentTimeMillis());
        longRunningProcessQueueItem.put("userId", user.getUserId());
        LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.setProperty(vertexBuilder, longRunningProcessQueueItem, visibility);
        Vertex longRunningProcessVertex = vertexBuilder.save(authorizations);

        this.graph.addEdge(userVertex, longRunningProcessVertex, LongRunningProcessProperties.LONG_RUNNING_PROCESS_TO_USER_EDGE_IRI, visibility, authorizations);

        this.graph.flush();

        longRunningProcessQueueItem.put("id", longRunningProcessVertex.getId());
        this.workQueueRepository.pushLongRunningProcessQueue(longRunningProcessQueueItem);

        return longRunningProcessVertex.getId();
    }

    public Authorizations getAuthorizations(User user) {
        Authorizations authorizations;
        authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, UserRepository.VISIBILITY_STRING);
        return authorizations;
    }

    @Override
    public void beginWork(JSONObject longRunningProcessQueueItem) {
        super.beginWork(longRunningProcessQueueItem);
        updateVertexWithJson(longRunningProcessQueueItem);
    }

    @Override
    public void ack(JSONObject longRunningProcessQueueItem) {
        updateVertexWithJson(longRunningProcessQueueItem);
    }

    @Override
    public void nak(JSONObject longRunningProcessQueueItem, Throwable ex) {
        updateVertexWithJson(longRunningProcessQueueItem);
    }

    public void updateVertexWithJson(JSONObject longRunningProcessQueueItem) {
        String longRunningProcessGraphVertexId = longRunningProcessQueueItem.getString("id");
        Authorizations authorizations = getAuthorizations(userRepository.getSystemUser());
        Vertex vertex = this.graph.getVertex(longRunningProcessGraphVertexId, authorizations);
        checkNotNull(vertex, "Could not find long running process vertex: " + longRunningProcessGraphVertexId);
        LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.setProperty(vertex, longRunningProcessQueueItem, getVisibility(), authorizations);
        this.graph.flush();
    }

    @Override
    public List<JSONObject> getLongRunningProcesses(User user) {
        Authorizations authorizations = getAuthorizations(user);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user with id: " + user.getUserId());
        Iterable<Vertex> longRunningProcessVertices = userVertex.getVertices(Direction.OUT, LongRunningProcessProperties.LONG_RUNNING_PROCESS_TO_USER_EDGE_IRI, authorizations);
        return toList(new ConvertingIterable<Vertex, JSONObject>(longRunningProcessVertices) {
            @Override
            protected JSONObject convert(Vertex longRunningProcessVertex) {
                JSONObject json = LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.getPropertyValue(longRunningProcessVertex);
                json.put("id", longRunningProcessVertex.getId());
                return json;
            }
        });
    }

    @Override
    public JSONObject findById(String longRunningProcessId, User user) {
        Authorizations authorizations = getAuthorizations(user);
        Vertex vertex = this.graph.getVertex(longRunningProcessId, authorizations);
        if (vertex == null) {
            return null;
        }
        return LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.getPropertyValue(vertex);
    }

    @Override
    public void cancel(String longRunningProcessId, User user) {
        Authorizations authorizations = getAuthorizations(userRepository.getSystemUser());
        Vertex vertex = this.graph.getVertex(longRunningProcessId, authorizations);
        checkNotNull(vertex, "Could not find long running process vertex: " + longRunningProcessId);
        JSONObject json = LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.getPropertyValue(vertex);
        json.put("canceled", true);
        LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.setProperty(vertex, json, getVisibility(), getAuthorizations(user));
        this.graph.flush();
    }

    @Override
    public void reportProgress(JSONObject longRunningProcessQueueItem, double progressPercent, String message) {
        String longRunningProcessGraphVertexId = longRunningProcessQueueItem.getString("id");
        Authorizations authorizations = getAuthorizations(userRepository.getSystemUser());
        Vertex vertex = this.graph.getVertex(longRunningProcessGraphVertexId, authorizations);
        checkNotNull(vertex, "Could not find long running process vertex: " + longRunningProcessGraphVertexId);

        JSONObject json = LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.getPropertyValue(vertex);
        json.put("progress", progressPercent);
        json.put("progressMessage", message);
        LongRunningProcessProperties.QUEUE_ITEM_JSON_PROPERTY.setProperty(vertex, json, getVisibility(), authorizations);
        this.graph.flush();

        workQueueRepository.broadcastLongRunningProcessChange(json);
    }

    @Override
    public void delete(String longRunningProcessId, User authUser) {
        Authorizations authorizations = getAuthorizations(authUser);
        Vertex vertex = this.graph.getVertex(longRunningProcessId, authorizations);
        this.graph.softDeleteVertex(vertex, authorizations);
        this.graph.flush();
    }

    private Visibility getVisibility() {
        return new Visibility(VISIBILITY_STRING);
    }
}