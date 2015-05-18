package org.visallo.web.routes.edge;

import com.google.inject.Inject;
import com.v5analytics.webster.HandlerChain;
import org.visallo.core.config.Configuration;
import org.visallo.core.model.audit.AuditAction;
import org.visallo.core.model.audit.AuditRepository;
import org.visallo.core.model.graph.GraphRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.workQueue.Priority;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.model.workspace.WorkspaceRepository;
import org.visallo.core.user.User;
import org.visallo.core.util.ClientApiConverter;
import org.visallo.core.util.JsonSerializer;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.BaseRequestHandler;
import org.visallo.web.WebConfiguration;
import org.visallo.web.clientapi.model.ClientApiSourceInfo;
import org.vertexium.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class EdgeCreate extends BaseRequestHandler {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(EdgeCreate.class);

    private final Graph graph;
    private final AuditRepository auditRepository;
    private final WorkQueueRepository workQueueRepository;
    private final GraphRepository graphRepository;

    @Inject
    public EdgeCreate(
            final Graph graph,
            final AuditRepository auditRepository,
            final WorkspaceRepository workspaceRepository,
            final WorkQueueRepository workQueueRepository,
            final UserRepository userRepository,
            final Configuration configuration,
            final GraphRepository graphRepository
    ) {
        super(userRepository, workspaceRepository, configuration);
        this.graph = graph;
        this.auditRepository = auditRepository;
        this.workQueueRepository = workQueueRepository;
        this.graphRepository = graphRepository;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        final String sourceGraphVertexId = getRequiredParameter(request, "sourceGraphVertexId");
        final String destGraphVertexId = getRequiredParameter(request, "destGraphVertexId");
        final String predicateLabel = getRequiredParameter(request, "predicateLabel");
        final String visibilitySource = getRequiredParameter(request, "visibilitySource");
        final String justificationText = WebConfiguration.justificationRequired(getConfiguration()) ? getRequiredParameter(request, "justificationText") : getOptionalParameter(request, "justificationText");
        final String sourceInfoString = getOptionalParameter(request, "sourceInfo");

        String workspaceId = getActiveWorkspaceId(request);

        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);
        Vertex destVertex = graph.getVertex(destGraphVertexId, authorizations);
        Vertex sourceVertex = graph.getVertex(sourceGraphVertexId, authorizations);

        if (!graph.isVisibilityValid(new Visibility(visibilitySource), authorizations)) {
            LOGGER.warn("%s is not a valid visibility for %s user", visibilitySource, user.getDisplayName());
            respondWithBadRequest(response, "visibilitySource", getString(request, "visibility.invalid"));
            chain.next(request, response);
            return;
        }

        Edge edge = graphRepository.addEdge(
                sourceVertex,
                destVertex,
                predicateLabel,
                justificationText,
                ClientApiSourceInfo.fromString(sourceInfoString),
                visibilitySource,
                workspaceId,
                user,
                authorizations
        );

        // TODO: replace second "" when we implement commenting on ui
        auditRepository.auditRelationship(AuditAction.CREATE, sourceVertex, destVertex, edge, "", "",
                user, edge.getVisibility());

        graph.flush();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Statement created:\n" + JsonSerializer.toJson(edge, workspaceId, authorizations).toString(2));
        }

        workQueueRepository.pushElement(edge, Priority.HIGH);

        respondWithClientApiObject(response, ClientApiConverter.toClientApi(edge, workspaceId, authorizations));
    }
}