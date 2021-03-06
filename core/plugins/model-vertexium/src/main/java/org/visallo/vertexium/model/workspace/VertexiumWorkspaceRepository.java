package org.visallo.vertexium.model.workspace;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.vertexium.*;
import org.vertexium.mutation.ExistingEdgeMutation;
import org.vertexium.search.IndexHint;
import org.vertexium.util.ConvertingIterable;
import org.vertexium.util.FilterIterable;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.exception.VisalloResourceNotFoundException;
import org.visallo.core.formula.FormulaEvaluator;
import org.visallo.core.model.lock.LockRepository;
import org.visallo.core.model.ontology.OntologyRepository;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.termMention.TermMentionRepository;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.model.workspace.*;
import org.visallo.core.model.workspace.diff.WorkspaceDiffHelper;
import org.visallo.core.security.VisalloVisibility;
import org.visallo.core.security.VisibilityTranslator;
import org.visallo.core.trace.Traced;
import org.visallo.core.user.SystemUser;
import org.visallo.core.user.User;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.vertexium.model.user.VertexiumUserRepository;
import org.visallo.web.clientapi.model.ClientApiWorkspaceDiff;
import org.visallo.web.clientapi.model.GraphPosition;
import org.visallo.web.clientapi.model.WorkspaceAccess;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.vertexium.util.IterableUtils.toList;

@Singleton
public class VertexiumWorkspaceRepository extends WorkspaceRepository {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(VertexiumWorkspaceRepository.class);
    private UserRepository userRepository;
    private AuthorizationRepository authorizationRepository;
    private WorkspaceDiffHelper workspaceDiff;
    private final LockRepository lockRepository;
    private Cache<String, Boolean> usersWithReadAccessCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private Cache<String, Boolean> usersWithCommentAccessCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private Cache<String, Boolean> usersWithWriteAccessCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private Cache<String, List<WorkspaceUser>> usersWithAccessCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private Cache<String, Vertex> userWorkspaceVertexCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private Cache<String, List<WorkspaceEntity>> workspaceEntitiesCached = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();

    public void clearCache() {
        usersWithReadAccessCache.invalidateAll();
        usersWithCommentAccessCache.invalidateAll();
        usersWithWriteAccessCache.invalidateAll();
        usersWithAccessCache.invalidateAll();
        userWorkspaceVertexCache.invalidateAll();
        workspaceEntitiesCached.invalidateAll();
    }

    @Inject
    public VertexiumWorkspaceRepository(
            final Graph graph,
            final UserRepository userRepository,
            final AuthorizationRepository authorizationRepository,
            final WorkspaceDiffHelper workspaceDiff,
            final LockRepository lockRepository,
            final VisibilityTranslator visibilityTranslator,
            final TermMentionRepository termMentionRepository,
            final OntologyRepository ontologyRepository,
            final WorkQueueRepository workQueueRepository
    ) {
        super(
                graph,
                visibilityTranslator,
                termMentionRepository,
                ontologyRepository,
                workQueueRepository
        );
        this.userRepository = userRepository;
        this.authorizationRepository = authorizationRepository;
        this.workspaceDiff = workspaceDiff;
        this.lockRepository = lockRepository;

        authorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
        authorizationRepository.addAuthorizationToGraph(VisalloVisibility.SUPER_USER_VISIBILITY_STRING);
    }

    @Override
    public void delete(final Workspace workspace, final User user) {
        if (!hasWritePermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }

        lockRepository.lock(getLockName(workspace), new Runnable() {
            @Override
            public void run() {
                Authorizations authorizations = userRepository.getAuthorizations(user, UserRepository.VISIBILITY_STRING, VisalloVisibility.SUPER_USER_VISIBILITY_STRING, workspace.getWorkspaceId());
                Vertex workspaceVertex = getVertexFromWorkspace(workspace, true, authorizations);
                getGraph().softDeleteVertex(workspaceVertex, authorizations);
                getGraph().flush();

                authorizationRepository.removeAuthorizationFromGraph(workspace.getWorkspaceId());
            }
        });
    }

    private String getLockName(Workspace workspace) {
        return getLockName(workspace.getWorkspaceId());
    }

    private String getLockName(String workspaceId) {
        return "WORKSPACE_" + workspaceId;
    }

    public Vertex getVertex(String workspaceId, User user) {
        String cacheKey = getUserWorkspaceVertexCacheKey(workspaceId, user);
        Vertex workspaceVertex = userWorkspaceVertexCache.getIfPresent(cacheKey);
        if (workspaceVertex != null) {
            return workspaceVertex;
        }

        Authorizations authorizations = userRepository.getAuthorizations(user, UserRepository.VISIBILITY_STRING, VisalloVisibility.SUPER_USER_VISIBILITY_STRING, workspaceId);
        workspaceVertex = getGraph().getVertex(workspaceId, authorizations);
        userWorkspaceVertexCache.put(cacheKey, workspaceVertex);
        return workspaceVertex;
    }

    public String getUserWorkspaceVertexCacheKey(String workspaceId, User user) {
        return workspaceId + user.getUserId();
    }

    private Vertex getVertexFromWorkspace(Workspace workspace, boolean includeHidden, Authorizations authorizations) {
        if (workspace instanceof VertexiumWorkspace) {
            return ((VertexiumWorkspace) workspace).getVertex(getGraph(), includeHidden, authorizations);
        }
        return getGraph().getVertex(workspace.getWorkspaceId(), includeHidden ? FetchHint.ALL_INCLUDING_HIDDEN : FetchHint.ALL, authorizations);
    }

    @Override
    @Traced
    public Workspace findById(String workspaceId, boolean includeHidden, User user) {
        LOGGER.debug("findById(workspaceId: %s, userId: %s)", workspaceId, user.getUserId());
        Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspaceId);
        Vertex workspaceVertex = getGraph().getVertex(workspaceId, includeHidden ? FetchHint.ALL_INCLUDING_HIDDEN : FetchHint.ALL, authorizations);
        if (workspaceVertex == null) {
            return null;
        }
        if (!hasReadPermissions(workspaceId, user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have read access to workspace " + workspaceId, user, workspaceId);
        }
        return new VertexiumWorkspace(workspaceVertex);
    }

    @Override
    public Workspace add(String workspaceId, String title, User user) {
        authorizationRepository.addAuthorizationToGraph(workspaceId);

        Authorizations authorizations = userRepository.getAuthorizations(user, UserRepository.VISIBILITY_STRING, VISIBILITY_STRING, workspaceId);
        Vertex userVertex = getGraph().getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user: " + user.getUserId());

        VertexBuilder workspaceVertexBuilder = getGraph().prepareVertex(workspaceId, VISIBILITY.getVisibility());
        VisalloProperties.CONCEPT_TYPE.setProperty(workspaceVertexBuilder, WORKSPACE_CONCEPT_IRI, VISIBILITY.getVisibility());
        WorkspaceProperties.TITLE.setProperty(workspaceVertexBuilder, title, VISIBILITY.getVisibility());
        Vertex workspaceVertex = workspaceVertexBuilder.save(authorizations);

        addWorkspaceToUser(workspaceVertex, userVertex, authorizations);

        getGraph().flush();
        return new VertexiumWorkspace(workspaceVertex);
    }

    public void addWorkspaceToUser(Vertex workspaceVertex, Vertex userVertex, Authorizations authorizations) {
        EdgeBuilder edgeBuilder = getGraph().prepareEdge(workspaceVertex, userVertex, WORKSPACE_TO_USER_RELATIONSHIP_IRI, VISIBILITY.getVisibility());
        WorkspaceProperties.WORKSPACE_TO_USER_IS_CREATOR.setProperty(edgeBuilder, true, VISIBILITY.getVisibility());
        WorkspaceProperties.WORKSPACE_TO_USER_ACCESS.setProperty(edgeBuilder, WorkspaceAccess.WRITE.toString(), VISIBILITY.getVisibility());
        edgeBuilder.save(authorizations);
    }

    @Override
    public Iterable<Workspace> findAllForUser(final User user) {
        checkNotNull(user, "User is required");
        Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, UserRepository.VISIBILITY_STRING);
        Vertex userVertex = getGraph().getVertex(user.getUserId(), authorizations);
        checkNotNull(userVertex, "Could not find user vertex with id " + user.getUserId());
        return toList(new ConvertingIterable<Vertex, Workspace>(userVertex.getVertices(Direction.IN, WORKSPACE_TO_USER_RELATIONSHIP_IRI, authorizations)) {
            @Override
            protected Workspace convert(Vertex workspaceVertex) {
                String cacheKey = getUserWorkspaceVertexCacheKey(workspaceVertex.getId(), user);
                userWorkspaceVertexCache.put(cacheKey, workspaceVertex);
                return new VertexiumWorkspace(workspaceVertex);
            }
        });
    }

    @Override
    public void setTitle(Workspace workspace, String title, User user) {
        if (!hasWritePermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }
        Authorizations authorizations = userRepository.getAuthorizations(user);
        Vertex workspaceVertex = getVertexFromWorkspace(workspace, false, authorizations);
        WorkspaceProperties.TITLE.setProperty(workspaceVertex, title, VISIBILITY.getVisibility(), authorizations);
        getGraph().flush();
    }

    @Override
    @Traced
    public List<WorkspaceUser> findUsersWithAccess(final String workspaceId, final User user) {
        String cacheKey = workspaceId + user.getUserId();
        List<WorkspaceUser> usersWithAccess = this.usersWithAccessCache.getIfPresent(cacheKey);
        if (usersWithAccess != null) {
            return usersWithAccess;
        }

        LOGGER.debug("BEGIN findUsersWithAccess query");
        Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspaceId);
        Vertex workspaceVertex = getVertex(workspaceId, user);
        Iterable<Edge> userEdges = workspaceVertex.getEdges(Direction.BOTH, WORKSPACE_TO_USER_RELATIONSHIP_IRI, authorizations);
        usersWithAccess = toList(new ConvertingIterable<Edge, WorkspaceUser>(userEdges) {
            @Override
            protected WorkspaceUser convert(Edge edge) {
                String userId = edge.getOtherVertexId(workspaceId);

                String accessString = WorkspaceProperties.WORKSPACE_TO_USER_ACCESS.getPropertyValue(edge);
                WorkspaceAccess workspaceAccess = WorkspaceAccess.NONE;
                if (accessString != null && accessString.length() > 0) {
                    workspaceAccess = WorkspaceAccess.valueOf(accessString);
                }

                boolean isCreator = WorkspaceProperties.WORKSPACE_TO_USER_IS_CREATOR.getPropertyValue(edge, false);

                return new WorkspaceUser(userId, workspaceAccess, isCreator);
            }
        });
        this.usersWithAccessCache.put(cacheKey, usersWithAccess);
        LOGGER.debug("END findUsersWithAccess query");
        return usersWithAccess;
    }

    @Override
    public List<WorkspaceEntity> findEntities(final Workspace workspace, final User user) {
        if (!hasReadPermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have read access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }

        return lockRepository.lock(getLockName(workspace), new Callable<List<WorkspaceEntity>>() {
            @Override
            public List<WorkspaceEntity> call() throws Exception {
                return findEntitiesNoLock(workspace, false, false, user);
            }
        });
    }

    @Traced
    private List<WorkspaceEntity> findEntitiesNoLock(final Workspace workspace, final boolean includeHidden, final boolean fetchVertices, User user) {
        LOGGER.debug("BEGIN findEntitiesNoLock(workspaceId: %s, includeHidden: %b, userId: %s)", workspace.getWorkspaceId(), includeHidden, user.getUserId());
        long startTime = System.currentTimeMillis();
        String cacheKey = workspace.getWorkspaceId() + includeHidden + user.getUserId();
        List<WorkspaceEntity> results = workspaceEntitiesCached.getIfPresent(cacheKey);
        if (results != null) {
            LOGGER.debug("END findEntitiesNoLock (cache hit, found: %d entities)", results.size());
            return results;
        }

        Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspace.getWorkspaceId());
        Vertex workspaceVertex = getVertexFromWorkspace(workspace, includeHidden, authorizations);
        List<Edge> entityEdges = toList(workspaceVertex.getEdges(Direction.BOTH, WORKSPACE_TO_ENTITY_RELATIONSHIP_IRI, authorizations));

        final Map<String, Vertex> workspaceVertices;
        if (fetchVertices) {
            workspaceVertices = getWorkspaceVertices(workspace, entityEdges, authorizations);
        } else {
            workspaceVertices = null;
        }

        results = Lists.newArrayList(Iterables.filter(Iterables.transform(entityEdges, new Function<Edge, WorkspaceEntity>() {
            @Override
            public WorkspaceEntity apply(Edge edge) {
                String entityVertexId = edge.getOtherVertexId(workspace.getWorkspaceId());

                Integer graphPositionX = WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_POSITION_X.getPropertyValue(edge);
                Integer graphPositionY = WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_POSITION_Y.getPropertyValue(edge);
                String graphLayoutJson = WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_LAYOUT_JSON.getPropertyValue(edge);
                boolean visible = WorkspaceProperties.WORKSPACE_TO_ENTITY_VISIBLE.getPropertyValue(edge, false);
                if (!includeHidden && !visible) {
                    return null;
                }

                Vertex workspaceVertex = null;
                if (fetchVertices) {
                    workspaceVertex = workspaceVertices.get(entityVertexId);
                }
                return new WorkspaceEntity(entityVertexId, visible, graphPositionX, graphPositionY, graphLayoutJson, workspaceVertex);
            }
        }), Predicates.notNull()));
        workspaceEntitiesCached.put(cacheKey, results);
        LOGGER.debug("END findEntitiesNoLock (found: %d entities, time: %dms)", results.size(), System.currentTimeMillis() - startTime);
        return results;
    }

    protected Map<String, Vertex> getWorkspaceVertices(final Workspace workspace, List<Edge> entityEdges, Authorizations authorizations) {
        Map<String, Vertex> workspaceVertices;
        Iterable<String> workspaceVertexIds = Iterables.transform(entityEdges, new Function<Edge, String>() {
            @Nullable
            @Override
            public String apply(Edge edge) {
                return edge.getOtherVertexId(workspace.getWorkspaceId());
            }
        });
        Iterable<Vertex> vertices = getGraph().getVertices(workspaceVertexIds, FetchHint.ALL_INCLUDING_HIDDEN, authorizations);
        workspaceVertices = Maps.uniqueIndex(vertices, new Function<Vertex, String>() {
            @Nullable
            @Override
            public String apply(Vertex v) {
                return v.getId();
            }
        });
        return workspaceVertices;
    }

    @Traced
    protected Iterable<Edge> findModifiedEdges(final Workspace workspace, List<WorkspaceEntity> workspaceEntities, boolean includeHidden, User user) {
        Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspace.getWorkspaceId());

        Iterable<Vertex> vertices = Iterables.filter(WorkspaceEntity.toVertices(workspaceEntities, getGraph(), authorizations), Predicates.notNull());
        Iterable<String> edgeIds = getGraph().findRelatedEdgeIdsForVertices(vertices, authorizations);
        edgeIds = getGraph().filterEdgeIdsByAuthorization(edgeIds, workspace.getWorkspaceId(), ElementFilter.ALL, authorizations);

        return getGraph().getEdges(edgeIds, includeHidden ? FetchHint.ALL_INCLUDING_HIDDEN : FetchHint.ALL, authorizations);
    }

    @Override
    public Workspace copyTo(Workspace workspace, User destinationUser, User user) {
        Workspace newWorkspace = super.copyTo(workspace, destinationUser, user);
        getGraph().flush();
        return newWorkspace;
    }

    @Override
    public void softDeleteEntitiesFromWorkspace(Workspace workspace, List<String> entityIdsToDelete, User user) {
        if (entityIdsToDelete.size() == 0) {
            return;
        }
        if (!hasWritePermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }
        Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspace.getWorkspaceId());
        final Vertex workspaceVertex = getVertexFromWorkspace(workspace, true, authorizations);
        List<Edge> allEdges = toList(workspaceVertex.getEdges(Direction.BOTH, authorizations));

        for (final String vertexId : entityIdsToDelete) {
            LOGGER.debug("workspace delete (%s): %s", workspace.getWorkspaceId(), vertexId);

            Iterable<Edge> edges = new FilterIterable<Edge>(allEdges) {
                @Override
                protected boolean isIncluded(Edge o) {
                    String entityVertexId = o.getOtherVertexId(workspaceVertex.getId());
                    return entityVertexId.equalsIgnoreCase(vertexId);
                }
            };
            for (Edge edge : edges) {
                ExistingEdgeMutation m = edge.prepareMutation();
                WorkspaceProperties.WORKSPACE_TO_ENTITY_VISIBLE.setProperty(m, false, VISIBILITY.getVisibility());
                WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_LAYOUT_JSON.removeProperty(m, VISIBILITY.getVisibility());
                WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_POSITION_X.removeProperty(m, VISIBILITY.getVisibility());
                WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_POSITION_Y.removeProperty(m, VISIBILITY.getVisibility());
                m.save(authorizations);
            }
        }
        getGraph().flush();
    }

    @Override
    public void updateEntitiesOnWorkspace(final Workspace workspace, final Collection<Update> updates, final User user) {
        if (updates.size() == 0) {
            return;
        }
        if (!hasCommentPermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }

        lockRepository.lock(getLockName(workspace.getWorkspaceId()), new Runnable() {
            @Override
            public void run() {
                Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspace.getWorkspaceId());

                Vertex workspaceVertex = getVertexFromWorkspace(workspace, true, authorizations);
                if (workspaceVertex == null) {
                    throw new VisalloResourceNotFoundException("Could not find workspace vertex: " + workspace.getWorkspaceId(), workspace.getWorkspaceId());
                }

                Iterable<String> vertexIds = new ConvertingIterable<Update, String>(updates) {
                    @Override
                    protected String convert(Update o) {
                        return o.getVertexId();
                    }
                };
                Iterable<Vertex> vertices = getGraph().getVertices(vertexIds, authorizations);
                ImmutableMap<String, Vertex> verticesMap = Maps.uniqueIndex(vertices, new Function<Vertex, String>() {
                    @Override
                    public String apply(Vertex vertex) {
                        return vertex.getId();
                    }
                });

                for (Update update : updates) {
                    Vertex otherVertex = verticesMap.get(update.getVertexId());
                    checkNotNull(otherVertex, "Could not find vertex with id: " + update.getVertexId());
                    createEdge(workspaceVertex, otherVertex, update.getGraphPosition(), update.getGraphLayoutJson(), update.getVisible(), authorizations);
                }
                getGraph().flush();
                workspaceEntitiesCached.invalidateAll();
            }
        });
    }

    private void createEdge(Vertex workspaceVertex, Vertex otherVertex, GraphPosition graphPosition, String graphLayoutJson, Boolean visible, Authorizations authorizations) {
        String workspaceVertexId = workspaceVertex.getId();
        String entityVertexId = otherVertex.getId();
        String edgeId = getWorkspaceToEntityEdgeId(workspaceVertexId, entityVertexId);
        EdgeBuilder edgeBuilder = getGraph().prepareEdge(edgeId, workspaceVertex, otherVertex, WORKSPACE_TO_ENTITY_RELATIONSHIP_IRI, VISIBILITY.getVisibility());
        if (graphPosition != null) {
            WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_POSITION_X.setProperty(edgeBuilder, graphPosition.getX(), VISIBILITY.getVisibility());
            WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_POSITION_Y.setProperty(edgeBuilder, graphPosition.getY(), VISIBILITY.getVisibility());
        }
        if (graphLayoutJson != null) {
            WorkspaceProperties.WORKSPACE_TO_ENTITY_GRAPH_LAYOUT_JSON.setProperty(edgeBuilder, graphLayoutJson, VISIBILITY.getVisibility());
        }
        if (visible != null) {
            WorkspaceProperties.WORKSPACE_TO_ENTITY_VISIBLE.setProperty(edgeBuilder, visible, VISIBILITY.getVisibility());
        }
        edgeBuilder.setIndexHint(IndexHint.DO_NOT_INDEX);
        edgeBuilder.save(authorizations);
    }

    @Override
    public void deleteUserFromWorkspace(final Workspace workspace, final String userId, final User user) {
        if (!hasWritePermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }

        lockRepository.lock(getLockName(workspace), new Runnable() {
            @Override
            public void run() {
                Authorizations authorizations = userRepository.getAuthorizations(user, UserRepository.VISIBILITY_STRING, VISIBILITY_STRING, workspace.getWorkspaceId());
                Vertex userVertex = getGraph().getVertex(userId, authorizations);
                if (userVertex == null) {
                    throw new VisalloResourceNotFoundException("Could not find user: " + userId, userId);
                }
                Vertex workspaceVertex = getVertexFromWorkspace(workspace, true, authorizations);
                List<Edge> edges = toList(workspaceVertex.getEdges(userVertex, Direction.BOTH, WORKSPACE_TO_USER_RELATIONSHIP_IRI, authorizations));
                for (Edge edge : edges) {
                    getGraph().softDeleteEdge(edge, authorizations);
                }
                getGraph().flush();

                clearCache();
            }
        });
    }

    @Override
    public boolean hasCommentPermissions(String workspaceId, User user) {
        if (user instanceof SystemUser) {
            return true;
        }

        String cacheKey = workspaceId + user.getUserId();
        Boolean hasCommentAccess = usersWithCommentAccessCache.getIfPresent(cacheKey);
        if (hasCommentAccess != null && hasCommentAccess) {
            return true;
        }

        List<WorkspaceUser> usersWithAccess = findUsersWithAccess(workspaceId, user);
        for (WorkspaceUser userWithAccess : usersWithAccess) {
            if (userWithAccess.getUserId().equals(user.getUserId()) && (
                    userWithAccess.getWorkspaceAccess() == WorkspaceAccess.WRITE ||
                            userWithAccess.getWorkspaceAccess() == WorkspaceAccess.COMMENT
            )) {
                usersWithCommentAccessCache.put(cacheKey, true);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasWritePermissions(String workspaceId, User user) {
        if (user instanceof SystemUser) {
            return true;
        }

        String cacheKey = workspaceId + user.getUserId();
        Boolean hasWriteAccess = usersWithWriteAccessCache.getIfPresent(cacheKey);
        if (hasWriteAccess != null && hasWriteAccess) {
            return true;
        }

        List<WorkspaceUser> usersWithAccess = findUsersWithAccess(workspaceId, user);
        for (WorkspaceUser userWithAccess : usersWithAccess) {
            if (userWithAccess.getUserId().equals(user.getUserId()) && userWithAccess.getWorkspaceAccess() == WorkspaceAccess.WRITE) {
                usersWithWriteAccessCache.put(cacheKey, true);
                return true;
            }
        }
        return false;
    }

    @Override
    @Traced
    public boolean hasReadPermissions(String workspaceId, User user) {
        if (user instanceof SystemUser) {
            return true;
        }

        String cacheKey = workspaceId + user.getUserId();
        Boolean hasReadAccess = usersWithReadAccessCache.getIfPresent(cacheKey);
        if (hasReadAccess != null && hasReadAccess) {
            return true;
        }

        List<WorkspaceUser> usersWithAccess = findUsersWithAccess(workspaceId, user);
        for (WorkspaceUser userWithAccess : usersWithAccess) {
            if (userWithAccess.getUserId().equals(user.getUserId())
                    && (userWithAccess.getWorkspaceAccess() == WorkspaceAccess.WRITE || userWithAccess.getWorkspaceAccess() == WorkspaceAccess.READ || userWithAccess.getWorkspaceAccess() == WorkspaceAccess.COMMENT)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void updateUserOnWorkspace(final Workspace workspace, final String userId, final WorkspaceAccess workspaceAccess, final User user) {
        if (!hasWritePermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }

        lockRepository.lock(getLockName(workspace), new Runnable() {
            @Override
            public void run() {
                Authorizations authorizations = userRepository.getAuthorizations(user, VISIBILITY_STRING, workspace.getWorkspaceId());
                Vertex otherUserVertex;
                if (userRepository instanceof VertexiumUserRepository) {
                    otherUserVertex = ((VertexiumUserRepository) userRepository).findByIdUserVertex(userId);
                } else {
                    otherUserVertex = getGraph().getVertex(userId, authorizations);
                }
                if (otherUserVertex == null) {
                    throw new VisalloResourceNotFoundException("Could not find user: " + userId, userId);
                }

                Vertex workspaceVertex = getVertexFromWorkspace(workspace, true, authorizations);
                if (workspaceVertex == null) {
                    throw new VisalloResourceNotFoundException("Could not find workspace vertex: " + workspace.getWorkspaceId(), workspace.getWorkspaceId());
                }

                List<Edge> existingEdges = toList(workspaceVertex.getEdges(otherUserVertex, Direction.OUT, WORKSPACE_TO_USER_RELATIONSHIP_IRI, authorizations));
                if (existingEdges.size() > 0) {
                    for (Edge existingEdge : existingEdges) {
                        WorkspaceProperties.WORKSPACE_TO_USER_ACCESS.setProperty(existingEdge, workspaceAccess.toString(), VISIBILITY.getVisibility(), authorizations);
                    }
                } else {

                    EdgeBuilder edgeBuilder = getGraph().prepareEdge(workspaceVertex, otherUserVertex, WORKSPACE_TO_USER_RELATIONSHIP_IRI, VISIBILITY.getVisibility());
                    WorkspaceProperties.WORKSPACE_TO_USER_ACCESS.setProperty(edgeBuilder, workspaceAccess.toString(), VISIBILITY.getVisibility());
                    edgeBuilder.save(authorizations);
                }

                getGraph().flush();

                clearCache();
            }
        });
    }

    @Override
    @Traced
    public ClientApiWorkspaceDiff getDiff(final Workspace workspace, final User user, final Locale locale, final String timeZone) {
        if (!hasReadPermissions(workspace.getWorkspaceId(), user)) {
            throw new VisalloAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getWorkspaceId(), user, workspace.getWorkspaceId());
        }

        return lockRepository.lock(getLockName(workspace), new Callable<ClientApiWorkspaceDiff>() {
            @Override
            public ClientApiWorkspaceDiff call() throws Exception {
                List<WorkspaceEntity> workspaceEntities = findEntitiesNoLock(workspace, true, true, user);
                Iterable<Edge> workspaceEdges = findModifiedEdges(workspace, workspaceEntities, true, user);

                FormulaEvaluator.UserContext userContext = new FormulaEvaluator.UserContext(locale, timeZone, workspace.getWorkspaceId());
                return workspaceDiff.diff(workspace, workspaceEntities, workspaceEdges, userContext, user);
            }
        });
    }
}
