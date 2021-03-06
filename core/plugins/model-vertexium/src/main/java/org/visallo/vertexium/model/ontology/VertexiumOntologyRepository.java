package org.visallo.vertexium.model.ontology;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.ontology.*;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.util.JSONUtil;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.core.util.TimingCallable;
import org.visallo.web.clientapi.model.ClientApiOntology;
import org.visallo.web.clientapi.model.PropertyType;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.ReaderDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.vertexium.*;
import org.vertexium.property.StreamingPropertyValue;
import org.vertexium.util.ConvertingIterable;
import org.vertexium.util.FilterIterable;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class VertexiumOntologyRepository extends OntologyRepositoryBase {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(VertexiumOntologyRepository.class);
    public static final String ID_PREFIX = "ontology_";
    public static final String ID_PREFIX_PROPERTY = ID_PREFIX + "prop_";
    public static final String ID_PREFIX_RELATIONSHIP = ID_PREFIX + "rel_";
    public static final String ID_PREFIX_CONCEPT = ID_PREFIX + "concept_";
    public static final String DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME = "order";
    private Graph graph;
    private Authorizations authorizations;
    private Cache<String, List<Concept>> allConceptsWithPropertiesCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();
    protected Cache<String, List<OntologyProperty>> allPropertiesCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();
    private Cache<String, List<Relationship>> relationshipLabelsCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();
    private Cache<String, ClientApiOntology> clientApiCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();

    @Inject
    public VertexiumOntologyRepository(
            final Graph graph,
            final Configuration config,
            final AuthorizationRepository authorizationRepository
    ) throws Exception {
        super(config);
        this.graph = graph;

        authorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);

        Set<String> authorizationsSet = new HashSet<>();
        authorizationsSet.add(VISIBILITY_STRING);
        this.authorizations = graph.createAuthorizations(authorizationsSet);

        loadOntologies(config, authorizations);
    }

    @Override
    protected void importOntologyAnnotationProperty(OWLOntology o, OWLAnnotationProperty annotationProperty, File inDir, Authorizations authorizations) {
        super.importOntologyAnnotationProperty(o, annotationProperty, inDir, authorizations);

        String about = annotationProperty.getIRI().toString();
        LOGGER.debug("disabling index for annotation property: " + about);
        DefinePropertyBuilder definePropertyBuilder = graph.defineProperty(about);
        definePropertyBuilder.dataType(PropertyType.getTypeClass(PropertyType.STRING));
        definePropertyBuilder.textIndexHint(TextIndexHint.NONE);
        definePropertyBuilder.define();
    }

    @Override
    public ClientApiOntology getClientApiObject() {
        ClientApiOntology o = this.clientApiCache.getIfPresent("clientApi");
        if (o != null) {
            return o;
        }
        o = super.getClientApiObject();
        this.clientApiCache.put("clientApi", o);
        return o;
    }

    @Override
    public void clearCache() {
        LOGGER.info("clearing ontology cache");
        graph.flush();
        this.clientApiCache.invalidateAll();
        this.allConceptsWithPropertiesCache.invalidateAll();
        this.allPropertiesCache.invalidateAll();
        this.relationshipLabelsCache.invalidateAll();
    }

    @Override
    protected void addEntityGlyphIconToEntityConcept(Concept entityConcept, byte[] rawImg) {
        StreamingPropertyValue raw = new StreamingPropertyValue(new ByteArrayInputStream(rawImg), byte[].class);
        raw.searchIndex(false);
        entityConcept.setProperty(OntologyProperties.GLYPH_ICON.getPropertyName(), raw, authorizations);
        graph.flush();
    }

    @Override
    public void storeOntologyFile(InputStream in, IRI documentIRI) {
        StreamingPropertyValue value = new StreamingPropertyValue(in, byte[].class);
        value.searchIndex(false);
        Metadata metadata = new Metadata();
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept()).getVertex();
        metadata.add("index", Iterables.size(OntologyProperties.ONTOLOGY_FILE.getProperties(rootConceptVertex)), VISIBILITY.getVisibility());
        OntologyProperties.ONTOLOGY_FILE.addPropertyValue(rootConceptVertex, documentIRI.toString(), value, metadata, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    public boolean isOntologyDefined(String iri) {
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept()).getVertex();
        Property prop = OntologyProperties.ONTOLOGY_FILE.getProperty(rootConceptVertex, iri);
        return prop != null;
    }

    @Override
    public List<OWLOntology> loadOntologyFiles(OWLOntologyManager m, OWLOntologyLoaderConfiguration config, IRI excludedIRI) throws OWLOntologyCreationException, IOException {
        List<OWLOntology> loadedOntologies = new ArrayList<>();
        Iterable<Property> ontologyFiles = getOntologyFiles();
        for (Property ontologyFile : ontologyFiles) {
            IRI ontologyFileIRI = IRI.create(ontologyFile.getKey());
            if (excludedIRI != null && excludedIRI.equals(ontologyFileIRI)) {
                continue;
            }
            try (InputStream visalloBaseOntologyIn = ((StreamingPropertyValue) ontologyFile.getValue()).getInputStream()) {
                Reader visalloBaseOntologyReader = new InputStreamReader(visalloBaseOntologyIn);
                LOGGER.info("Loading existing ontology: %s", ontologyFile.getKey());
                OWLOntologyDocumentSource visalloBaseOntologySource = new ReaderDocumentSource(visalloBaseOntologyReader, ontologyFileIRI);
                try {
                    OWLOntology o = m.loadOntologyFromOntologyDocument(visalloBaseOntologySource, config);
                    loadedOntologies.add(o);
                } catch (UnloadableImportException ex) {
                    LOGGER.error("Could not load %s", ontologyFileIRI, ex);
                }
            }
        }
        return loadedOntologies;
    }

    private Iterable<Property> getOntologyFiles() {
        VertexiumConcept rootConcept = (VertexiumConcept) getRootConcept();
        checkNotNull(rootConcept, "Could not get root concept");
        Vertex rootConceptVertex = rootConcept.getVertex();
        checkNotNull(rootConceptVertex, "Could not get root concept vertex");

        List<Property> ontologyFiles = Lists.newArrayList(OntologyProperties.ONTOLOGY_FILE.getProperties(rootConceptVertex));
        Collections.sort(ontologyFiles, new Comparator<Property>() {
            @Override
            public int compare(Property ontologyFile1, Property ontologyFile2) {
                Integer index1 = (Integer) ontologyFile1.getMetadata().getValue("index");
                checkNotNull(index1, "Could not find metadata (1) 'index' on " + ontologyFile1);
                Integer index2 = (Integer) ontologyFile2.getMetadata().getValue("index");
                checkNotNull(index2, "Could not find metadata (2) 'index' on " + ontologyFile2);
                return index1.compareTo(index2);
            }
        });
        return ontologyFiles;
    }

    @Override
    public Iterable<Relationship> getRelationships() {
        try {
            return relationshipLabelsCache.get("", new TimingCallable<List<Relationship>>("getRelationships") {
                @Override
                public List<Relationship> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_RELATIONSHIP, getAuthorizations());
                    vertices = Iterables.filter(vertices, new Predicate<Vertex>() {
                        @Override
                        public boolean apply(@Nullable Vertex vertex) {
                            return VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_RELATIONSHIP);
                        }
                    });
                    return Lists.newArrayList(Iterables.transform(vertices, new Function<Vertex, Relationship>() {
                        @Nullable
                        @Override
                        public Relationship apply(@Nullable Vertex vertex) {
                            return toVertexiumRelationship(vertex);
                        }
                    }));
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("Could not get relationship labels");
        }
    }

    private Relationship toVertexiumRelationship(Vertex relationshipVertex) {
        Iterable<Vertex> domainVertices = relationshipVertex.getVertices(Direction.IN, LabelName.HAS_EDGE.toString(), getAuthorizations());
        List<String> domainConceptIris = Lists.newArrayList(new ConvertingIterable<Vertex, String>(domainVertices) {
            @Override
            protected String convert(Vertex domainVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(domainVertex);
            }
        });

        Iterable<Vertex> rangeVertices = relationshipVertex.getVertices(Direction.OUT, LabelName.HAS_EDGE.toString(), getAuthorizations());
        List<String> rangeConceptIris = Lists.newArrayList(new ConvertingIterable<Vertex, String>(rangeVertices) {
            @Override
            protected String convert(Vertex rangeVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(rangeVertex);
            }
        });

        final List<String> inverseOfIRIs = getRelationshipInverseOfIRIs(relationshipVertex);
        return createRelationship(relationshipVertex, inverseOfIRIs, domainConceptIris, rangeConceptIris);
    }

    private List<String> getRelationshipInverseOfIRIs(final Vertex vertex) {
        return Lists.newArrayList(new ConvertingIterable<Vertex, String>(vertex.getVertices(Direction.OUT, LabelName.INVERSE_OF.toString(), getAuthorizations())) {
            @Override
            protected String convert(Vertex inverseOfVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(inverseOfVertex);
            }
        });
    }

    @Override
    public String getDisplayNameForLabel(String relationshipIRI) {
        String displayName = null;
        if (relationshipIRI != null && !relationshipIRI.trim().isEmpty()) {
            try {
                Relationship relationship = getRelationshipByIRI(relationshipIRI);
                if (relationship != null) {
                    displayName = relationship.getDisplayName();
                }
            } catch (IllegalArgumentException iae) {
                throw new IllegalStateException(String.format("Found multiple vertices for relationship label \"%s\"", relationshipIRI),
                        iae);
            }
        }
        return displayName;
    }

    @Override
    public Iterable<OntologyProperty> getProperties() {
        try {
            return allPropertiesCache.get("", new TimingCallable<List<OntologyProperty>>("getProperties") {
                @Override
                public List<OntologyProperty> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_PROPERTY, getAuthorizations());
                    vertices = Iterables.filter(vertices, new Predicate<Vertex>() {
                        @Override
                        public boolean apply(@Nullable Vertex vertex) {
                            return VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_PROPERTY);
                        }
                    });
                    return Lists.newArrayList(Iterables.transform(vertices, new Function<Vertex, OntologyProperty>() {
                        @Nullable
                        @Override
                        public OntologyProperty apply(@Nullable Vertex vertex) {
                            return createOntologyProperty(vertex, getDependentPropertyIris(vertex));
                        }
                    }));
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("Could not get properties", e);
        }
    }

    protected ImmutableList<String> getDependentPropertyIris(final Vertex vertex) {
        List<Edge> dependentProperties = Lists.newArrayList(vertex.getEdges(Direction.OUT, OntologyProperties.EDGE_LABEL_DEPENDENT_PROPERTY, getAuthorizations()));
        Collections.sort(dependentProperties, new Comparator<Edge>() {
            @Override
            public int compare(Edge e1, Edge e2) {
                Integer o1 = (Integer) e1.getPropertyValue(VertexiumOntologyRepository.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME);
                Integer o2 = (Integer) e2.getPropertyValue(VertexiumOntologyRepository.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME);
                return Integer.compare(o1, o2);
            }
        });
        return ImmutableList.copyOf(Iterables.transform(dependentProperties, new Function<Edge, String>() {
            @Nullable
            @Override
            public String apply(Edge e) {
                String propertyId = e.getOtherVertexId(vertex.getId());
                return propertyId.substring(VertexiumOntologyRepository.ID_PREFIX_PROPERTY.length());
            }
        }));
    }

    @Override
    public boolean hasRelationshipByIRI(String relationshipIRI) {
        return getRelationshipByIRI(relationshipIRI) != null;
    }

    @Override
    public Iterable<Concept> getConceptsWithProperties() {
        try {
            return allConceptsWithPropertiesCache.get("", new TimingCallable<List<Concept>>("getConceptsWithProperties") {
                @Override
                public List<Concept> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_CONCEPT, getAuthorizations());
                    vertices = Iterables.filter(vertices, new Predicate<Vertex>() {
                        @Override
                        public boolean apply(@Nullable Vertex vertex) {
                            return VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_CONCEPT);
                        }
                    });
                    return Lists.newArrayList(Iterables.transform(vertices, new Function<Vertex, Concept>() {
                        @Nullable
                        @Override
                        public Concept apply(@Nullable Vertex vertex) {
                            List<OntologyProperty> conceptProperties = getPropertiesByVertexNoRecursion(vertex);
                            Vertex parentConceptVertex = getParentConceptVertex(vertex);
                            String parentConceptIRI = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(parentConceptVertex);
                            return createConcept(vertex, conceptProperties, parentConceptIRI);
                        }
                    }));
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("could not get concepts with properties", e);
        }
    }

    private Concept getRootConcept() {
        return getConceptByIRI(VertexiumOntologyRepository.ROOT_CONCEPT_IRI);
    }

    @Override
    public Concept getEntityConcept() {
        return getConceptByIRI(VertexiumOntologyRepository.ENTITY_CONCEPT_IRI);
    }

    @Override
    protected List<Concept> getChildConcepts(Concept concept) {
        Vertex conceptVertex = ((VertexiumConcept) concept).getVertex();
        return toConcepts(conceptVertex.getVertices(Direction.IN, LabelName.IS_A.toString(), getAuthorizations()));
    }

    @Override
    public Concept getParentConcept(final Concept concept) {
        Vertex parentConceptVertex = getParentConceptVertex(((VertexiumConcept) concept).getVertex());
        if (parentConceptVertex == null) {
            return null;
        }
        return createConcept(parentConceptVertex);
    }

    private List<Concept> toConcepts(Iterable<Vertex> vertices) {
        ArrayList<Concept> concepts = new ArrayList<>();
        for (Vertex vertex : vertices) {
            concepts.add(createConcept(vertex));
        }
        return concepts;
    }

    private List<OntologyProperty> getPropertiesByVertexNoRecursion(Vertex vertex) {
        return Lists.newArrayList(new ConvertingIterable<Vertex, OntologyProperty>(vertex.getVertices(Direction.OUT, LabelName.HAS_PROPERTY.toString(), getAuthorizations())) {
            @Override
            protected OntologyProperty convert(Vertex o) {
                return createOntologyProperty(o, getDependentPropertyIris(o));
            }
        });
    }

    @Override
    public Concept getOrCreateConcept(Concept parent, String conceptIRI, String displayName, File inDir) {
        Concept concept = getConceptByIRI(conceptIRI);
        if (concept != null) {
            return concept;
        }

        VertexBuilder builder = graph.prepareVertex(ID_PREFIX_CONCEPT + conceptIRI, VISIBILITY.getVisibility());
        VisalloProperties.CONCEPT_TYPE.setProperty(builder, TYPE_CONCEPT, VISIBILITY.getVisibility());
        OntologyProperties.ONTOLOGY_TITLE.setProperty(builder, conceptIRI, VISIBILITY.getVisibility());
        OntologyProperties.DISPLAY_NAME.setProperty(builder, displayName, VISIBILITY.getVisibility());
        if (conceptIRI.equals(OntologyRepository.ENTITY_CONCEPT_IRI)) {
            OntologyProperties.TITLE_FORMULA.setProperty(builder, "prop('http://visallo.org#title') || ''", VISIBILITY.getVisibility());
            OntologyProperties.SUBTITLE_FORMULA.setProperty(builder, "prop('http://visallo.org#source') || ''", VISIBILITY.getVisibility());
            OntologyProperties.TIME_FORMULA.setProperty(builder, "''", VISIBILITY.getVisibility());
        }
        Vertex vertex = builder.save(getAuthorizations());

        concept = createConcept(vertex);
        if (parent != null) {
            findOrAddEdge(((VertexiumConcept) concept).getVertex(), ((VertexiumConcept) parent).getVertex(), LabelName.IS_A.toString());
        }

        graph.flush();
        return concept;
    }

    protected void findOrAddEdge(Vertex fromVertex, final Vertex toVertex, String edgeLabel) {
        List<Vertex> matchingEdges = Lists.newArrayList(new FilterIterable<Vertex>(fromVertex.getVertices(Direction.OUT, edgeLabel, getAuthorizations())) {
            @Override
            protected boolean isIncluded(Vertex vertex) {
                return vertex.getId().equals(toVertex.getId());
            }
        });
        if (matchingEdges.size() > 0) {
            return;
        }
        String edgeId = fromVertex.getId() + "-" + toVertex.getId();
        fromVertex.getGraph().addEdge(edgeId, fromVertex, toVertex, edgeLabel, VISIBILITY.getVisibility(), getAuthorizations());
    }

    @Override
    public OntologyProperty addPropertyTo(
            List<Concept> concepts,
            String propertyIri,
            String displayName,
            PropertyType dataType,
            Map<String, String> possibleValues,
            Collection<TextIndexHint> textIndexHints,
            boolean userVisible,
            boolean searchable,
            boolean addable,
            String displayType,
            String propertyGroup,
            Double boost,
            String validationFormula,
            String displayFormula,
            ImmutableList<String> dependentPropertyIris,
            String[] intents
    ) {
        checkNotNull(concepts, "vertex was null");
        OntologyProperty property = getOrCreatePropertyType(
                propertyIri,
                dataType,
                displayName,
                possibleValues,
                textIndexHints,
                userVisible,
                searchable,
                addable,
                displayType,
                propertyGroup,
                boost,
                validationFormula,
                displayFormula,
                dependentPropertyIris,
                intents
        );
        checkNotNull(property, "Could not find property: " + propertyIri);

        for (Concept concept : concepts) {
            findOrAddEdge(((VertexiumConcept) concept).getVertex(), ((VertexiumOntologyProperty) property).getVertex(), LabelName.HAS_PROPERTY.toString());
        }

        graph.flush();
        return property;
    }

    @Override
    protected void getOrCreateInverseOfRelationship(Relationship fromRelationship, Relationship inverseOfRelationship) {
        checkNotNull(fromRelationship, "fromRelationship is required");
        checkNotNull(fromRelationship, "inverseOfRelationship is required");

        VertexiumRelationship fromRelationshipSg = (VertexiumRelationship) fromRelationship;
        VertexiumRelationship inverseOfRelationshipSg = (VertexiumRelationship) inverseOfRelationship;

        Vertex fromVertex = fromRelationshipSg.getVertex();
        checkNotNull(fromVertex, "fromVertex is required");

        Vertex inverseVertex = inverseOfRelationshipSg.getVertex();
        checkNotNull(inverseVertex, "inverseVertex is required");

        findOrAddEdge(fromVertex, inverseVertex, LabelName.INVERSE_OF.toString());
        findOrAddEdge(inverseVertex, fromVertex, LabelName.INVERSE_OF.toString());
    }

    @Override
    public Relationship getOrCreateRelationshipType(
            Iterable<Concept> domainConcepts,
            Iterable<Concept> rangeConcepts,
            String relationshipIRI,
            String displayName,
            String[] intents,
            boolean userVisible
    ) {
        Relationship relationship = getRelationshipByIRI(relationshipIRI);
        if (relationship != null) {
            return relationship;
        }

        VertexBuilder builder = graph.prepareVertex(ID_PREFIX_RELATIONSHIP + relationshipIRI, VISIBILITY.getVisibility());
        VisalloProperties.CONCEPT_TYPE.setProperty(builder, TYPE_RELATIONSHIP, VISIBILITY.getVisibility());
        OntologyProperties.ONTOLOGY_TITLE.setProperty(builder, relationshipIRI, VISIBILITY.getVisibility());
        OntologyProperties.DISPLAY_NAME.setProperty(builder, displayName, VISIBILITY.getVisibility());
        OntologyProperties.USER_VISIBLE.setProperty(builder, userVisible, VISIBILITY.getVisibility());
        for (String intent : intents) {
            OntologyProperties.INTENT.addPropertyValue(builder, intent, intent, VISIBILITY.getVisibility());
        }
        Vertex relationshipVertex = builder.save(getAuthorizations());

        for (Concept domainConcept : domainConcepts) {
            findOrAddEdge(((VertexiumConcept) domainConcept).getVertex(), relationshipVertex, LabelName.HAS_EDGE.toString());
        }

        for (Concept rangeConcept : rangeConcepts) {
            findOrAddEdge(relationshipVertex, ((VertexiumConcept) rangeConcept).getVertex(), LabelName.HAS_EDGE.toString());
        }

        List<String> inverseOfIRIs = new ArrayList<>(); // no inverse of because this relationship is new

        graph.flush();

        List<String> domainConceptIris = Lists.newArrayList(new ConvertingIterable<Concept, String>(domainConcepts) {
            @Override
            protected String convert(Concept o) {
                return o.getIRI();
            }
        });

        List<String> rangeConceptIris = Lists.newArrayList(new ConvertingIterable<Concept, String>(rangeConcepts) {
            @Override
            protected String convert(Concept o) {
                return o.getIRI();
            }
        });

        return createRelationship(relationshipVertex, inverseOfIRIs, domainConceptIris, rangeConceptIris);
    }

    private OntologyProperty getOrCreatePropertyType(
            final String propertyIri,
            final PropertyType dataType,
            final String displayName,
            Map<String, String> possibleValues,
            Collection<TextIndexHint> textIndexHints,
            boolean userVisible,
            boolean searchable,
            boolean addable,
            String displayType,
            String propertyGroup,
            Double boost,
            String validationFormula,
            String displayFormula,
            ImmutableList<String> dependentPropertyIris,
            String[] intents
    ) {
        OntologyProperty typeProperty = getPropertyByIRI(propertyIri);
        if (typeProperty == null) {
            searchable = determineSearchable(propertyIri, dataType, textIndexHints, searchable);
            definePropertyOnGraph(graph, propertyIri, dataType, textIndexHints, boost);

            String propertyVertexId = ID_PREFIX_PROPERTY + propertyIri;
            VertexBuilder builder = graph.prepareVertex(propertyVertexId, VISIBILITY.getVisibility());
            VisalloProperties.CONCEPT_TYPE.setProperty(builder, TYPE_PROPERTY, VISIBILITY.getVisibility());
            OntologyProperties.ONTOLOGY_TITLE.setProperty(builder, propertyIri, VISIBILITY.getVisibility());
            OntologyProperties.DATA_TYPE.setProperty(builder, dataType.toString(), VISIBILITY.getVisibility());
            OntologyProperties.USER_VISIBLE.setProperty(builder, userVisible, VISIBILITY.getVisibility());
            OntologyProperties.SEARCHABLE.setProperty(builder, searchable, VISIBILITY.getVisibility());
            OntologyProperties.ADDABLE.setProperty(builder, addable, VISIBILITY.getVisibility());
            if (boost != null) {
                OntologyProperties.BOOST.setProperty(builder, boost, VISIBILITY.getVisibility());
            }
            if (displayName != null && !displayName.trim().isEmpty()) {
                OntologyProperties.DISPLAY_NAME.setProperty(builder, displayName.trim(), VISIBILITY.getVisibility());
            }
            if (possibleValues != null) {
                OntologyProperties.POSSIBLE_VALUES.setProperty(builder, JSONUtil.toJson(possibleValues), VISIBILITY.getVisibility());
            }
            if (displayType != null && !displayType.trim().isEmpty()) {
                OntologyProperties.DISPLAY_TYPE.setProperty(builder, displayType, VISIBILITY.getVisibility());
            }
            if (propertyGroup != null && !propertyGroup.trim().isEmpty()) {
                OntologyProperties.PROPERTY_GROUP.setProperty(builder, propertyGroup, VISIBILITY.getVisibility());
            }
            if (validationFormula != null && !validationFormula.trim().isEmpty()) {
                OntologyProperties.VALIDATION_FORMULA.setProperty(builder, validationFormula, VISIBILITY.getVisibility());
            }
            if (displayFormula != null && !displayFormula.trim().isEmpty()) {
                OntologyProperties.DISPLAY_FORMULA.setProperty(builder, displayFormula, VISIBILITY.getVisibility());
            }
            if (dependentPropertyIris != null) {
                int i = 0;
                for (String dependentPropertyIri : dependentPropertyIris) {
                    String dependentPropertyVertexId = ID_PREFIX_PROPERTY + dependentPropertyIri;
                    String edgeId = propertyVertexId + "-dependentProperty-" + i;
                    graph.prepareEdge(edgeId, propertyVertexId, dependentPropertyVertexId, OntologyProperties.EDGE_LABEL_DEPENDENT_PROPERTY, VISIBILITY.getVisibility())
                            .setProperty(DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME, i, VISIBILITY.getVisibility())
                            .save(authorizations);
                    i++;
                }
                // TODO: if the list of dependent property iris gets smaller they will not get cleaned up.
            }
            for (String intent : intents) {
                OntologyProperties.INTENT.addPropertyValue(builder, intent, intent, VISIBILITY.getVisibility());
            }
            Vertex propertyVertex = builder.save(getAuthorizations());
            typeProperty = createOntologyProperty(propertyVertex, dependentPropertyIris);
            graph.flush();
        }
        return typeProperty;
    }

    private Vertex getParentConceptVertex(Vertex conceptVertex) {
        try {
            return Iterables.getOnlyElement(conceptVertex.getVertices(Direction.OUT, LabelName.IS_A.toString(), getAuthorizations()), null);
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException(String.format("Unexpected number of parents for concept %s",
                    OntologyProperties.TITLE.getPropertyValue(conceptVertex)), iae);
        }
    }

    protected Authorizations getAuthorizations() {
        return authorizations;
    }

    protected Graph getGraph() {
        return graph;
    }

    /**
     * Overridable so subclasses can supply a custom implementation of OntologyProperty.
     */
    protected OntologyProperty createOntologyProperty(Vertex propertyVertex,
                                                      ImmutableList<String> dependentPropertyIris) {
        return new VertexiumOntologyProperty(propertyVertex, dependentPropertyIris);
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Relationship.
     */
    protected Relationship createRelationship(Vertex relationshipVertex, List<String> inverseOfIRIs,
                                              List<String> domainConceptIris, List<String> rangeConceptIris) {
        return new VertexiumRelationship(relationshipVertex, domainConceptIris, rangeConceptIris, inverseOfIRIs);
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Concept.
     */
    protected Concept createConcept(Vertex vertex, List<OntologyProperty> conceptProperties, String parentConceptIRI) {
        return new VertexiumConcept(vertex, parentConceptIRI, conceptProperties);
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Concept.
     */
    protected Concept createConcept(Vertex vertex) {
        return new VertexiumConcept(vertex);
    }
}
