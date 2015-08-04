package org.vertexium;

import org.vertexium.event.GraphEvent;
import org.vertexium.event.GraphEventListener;
import org.vertexium.id.IdGenerator;
import org.vertexium.path.PathFindingAlgorithm;
import org.vertexium.path.RecursivePathFindingAlgorithm;
import org.vertexium.query.GraphQuery;
import org.vertexium.query.MultiVertexQuery;
import org.vertexium.query.SimilarToGraphQuery;
import org.vertexium.util.*;

import java.util.*;

import static org.vertexium.util.IterableUtils.count;

public abstract class GraphBase implements Graph {
    private static final VertexiumLogger LOGGER = VertexiumLoggerFactory.getLogger(GraphBase.class);
    protected static final VertexiumLogger QUERY_LOGGER = VertexiumLoggerFactory.getQueryLogger(Graph.class);
    private final PathFindingAlgorithm pathFindingAlgorithm = new RecursivePathFindingAlgorithm();
    private final List<GraphEventListener> graphEventListeners = new ArrayList<>();

    protected GraphBase() {

    }

    @Override
    public Vertex addVertex(Visibility visibility, Authorizations authorizations) {
        return prepareVertex(visibility).save(authorizations);
    }

    @Override
    public Vertex addVertex(String vertexId, Visibility visibility, Authorizations authorizations) {
        return prepareVertex(vertexId, visibility).save(authorizations);
    }

    @Override
    public Iterable<Vertex> addVertices(Iterable<ElementBuilder<Vertex>> vertices, Authorizations authorizations) {
        List<Vertex> addedVertices = new ArrayList<>();
        for (ElementBuilder<Vertex> vertexBuilder : vertices) {
            addedVertices.add(vertexBuilder.save(authorizations));
        }
        return addedVertices;
    }

    @Override
    public VertexBuilder prepareVertex(Visibility visibility) {
        return prepareVertex(getIdGenerator().nextId(), null, visibility);
    }

    @Override
    public abstract VertexBuilder prepareVertex(String vertexId, Long timestamp, Visibility visibility);

    @Override
    public VertexBuilder prepareVertex(Long timestamp, Visibility visibility) {
        return prepareVertex(getIdGenerator().nextId(), timestamp, visibility);
    }

    @Override
    public VertexBuilder prepareVertex(String vertexId, Visibility visibility) {
        return prepareVertex(vertexId, null, visibility);
    }

    @Override
    public boolean doesVertexExist(String vertexId, Authorizations authorizations) {
        return getVertex(vertexId, FetchHint.NONE, authorizations) != null;
    }

    @Override
    public Vertex getVertex(String vertexId, EnumSet<FetchHint> fetchHints, Authorizations authorizations) {
        return getVertex(vertexId, fetchHints, null, authorizations);
    }

    @Override
    public Vertex getVertex(String vertexId, EnumSet<FetchHint> fetchHints, Long endTime, Authorizations authorizations) {
        LOGGER.warn("Performing scan of all vertices! Override getVertex.");
        for (Vertex vertex : getVertices(fetchHints, endTime, authorizations)) {
            if (vertex.getId().equals(vertexId)) {
                return vertex;
            }
        }
        return null;
    }

    @Override
    public Vertex getVertex(String vertexId, Authorizations authorizations) throws VertexiumException {
        return getVertex(vertexId, FetchHint.ALL, authorizations);
    }

    @Override
    public Iterable<Vertex> getVerticesWithPrefix(String vertexIdPrefix, Authorizations authorizations) {
        return getVerticesWithPrefix(vertexIdPrefix, FetchHint.ALL, authorizations);
    }

    @Override
    public Iterable<Vertex> getVerticesWithPrefix(String vertexIdPrefix, EnumSet<FetchHint> fetchHints, Authorizations authorizations) {
        return getVerticesWithPrefix(vertexIdPrefix, fetchHints, null, authorizations);
    }

    @Override
    public Iterable<Vertex> getVerticesWithPrefix(final String vertexIdPrefix, EnumSet<FetchHint> fetchHints, Long endTime, Authorizations authorizations) {
        LOGGER.warn("Performing scan of all vertices! Override getVerticesWithPrefix.");
        Iterable<Vertex> vertices = getVertices(fetchHints, endTime, authorizations);
        return new FilterIterable<Vertex>(vertices) {
            @Override
            protected boolean isIncluded(Vertex v) {
                return v.getId().startsWith(vertexIdPrefix);
            }
        };
    }

    @Override
    public Iterable<Vertex> getVertices(final Iterable<String> ids, EnumSet<FetchHint> fetchHints, final Authorizations authorizations) {
        return getVertices(ids, fetchHints, null, authorizations);
    }

    @Override
    public Iterable<Vertex> getVertices(final Iterable<String> ids, EnumSet<FetchHint> fetchHints, Long endTime, final Authorizations authorizations) {
        LOGGER.warn("Getting each vertex one by one! Override getVertices(java.lang.Iterable<java.lang.String>, Authorizations)");
        return new LookAheadIterable<String, Vertex>() {
            @Override
            protected boolean isIncluded(String src, Vertex vertex) {
                return vertex != null;
            }

            @Override
            protected Vertex convert(String id) {
                return getVertex(id, authorizations);
            }

            @Override
            protected Iterator<String> createIterator() {
                return ids.iterator();
            }
        };
    }

    @Override
    public Map<String, Boolean> doVerticesExist(Iterable<String> ids, Authorizations authorizations) {
        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, false);
        }
        for (Vertex vertex : getVertices(ids, FetchHint.NONE, authorizations)) {
            results.put(vertex.getId(), true);
        }
        return results;
    }

    @Override
    public Iterable<Vertex> getVertices(final Iterable<String> ids, final Authorizations authorizations) {
        return getVertices(ids, FetchHint.ALL, authorizations);
    }

    @Override
    public List<Vertex> getVerticesInOrder(Iterable<String> ids, EnumSet<FetchHint> fetchHints, Authorizations authorizations) {
        final List<String> vertexIds = IterableUtils.toList(ids);
        List<Vertex> vertices = IterableUtils.toList(getVertices(vertexIds, authorizations));
        Collections.sort(vertices, new Comparator<Vertex>() {
            @Override
            public int compare(Vertex v1, Vertex v2) {
                Integer i1 = vertexIds.indexOf(v1.getId());
                Integer i2 = vertexIds.indexOf(v2.getId());
                return i1.compareTo(i2);
            }
        });
        return vertices;
    }

    @Override
    public List<Vertex> getVerticesInOrder(Iterable<String> ids, Authorizations authorizations) {
        return getVerticesInOrder(ids, FetchHint.ALL, authorizations);
    }

    @Override
    public Iterable<Vertex> getVertices(Authorizations authorizations) throws VertexiumException {
        return getVertices(FetchHint.ALL, authorizations);
    }

    @Override
    public Iterable<Vertex> getVertices(EnumSet<FetchHint> fetchHints, Authorizations authorizations) {
        return getVertices(fetchHints, null, authorizations);
    }

    @Override
    public abstract Iterable<Vertex> getVertices(EnumSet<FetchHint> fetchHints, Long endTime, Authorizations authorizations);

    @Override
    public Edge addEdge(Vertex outVertex, Vertex inVertex, String label, Visibility visibility, Authorizations authorizations) {
        return prepareEdge(outVertex, inVertex, label, visibility).save(authorizations);
    }

    @Override
    public Edge addEdge(String edgeId, Vertex outVertex, Vertex inVertex, String label, Visibility visibility, Authorizations authorizations) {
        return prepareEdge(edgeId, outVertex, inVertex, label, visibility).save(authorizations);
    }

    @Override
    public Edge addEdge(String outVertexId, String inVertexId, String label, Visibility visibility, Authorizations authorizations) {
        return prepareEdge(outVertexId, inVertexId, label, visibility).save(authorizations);
    }

    @Override
    public Edge addEdge(String edgeId, String outVertexId, String inVertexId, String label, Visibility visibility, Authorizations authorizations) {
        return prepareEdge(edgeId, outVertexId, inVertexId, label, visibility).save(authorizations);
    }

    @Override
    public EdgeBuilderByVertexId prepareEdge(String outVertexId, String inVertexId, String label, Visibility visibility) {
        return prepareEdge(getIdGenerator().nextId(), outVertexId, inVertexId, label, visibility);
    }

    @Override
    public EdgeBuilder prepareEdge(Vertex outVertex, Vertex inVertex, String label, Visibility visibility) {
        return prepareEdge(getIdGenerator().nextId(), outVertex, inVertex, label, visibility);
    }

    @Override
    public EdgeBuilder prepareEdge(String edgeId, Vertex outVertex, Vertex inVertex, String label, Visibility visibility) {
        return prepareEdge(edgeId, outVertex, inVertex, label, null, visibility);
    }

    @Override
    public abstract EdgeBuilder prepareEdge(String edgeId, Vertex outVertex, Vertex inVertex, String label, Long timestamp, Visibility visibility);

    @Override
    public EdgeBuilderByVertexId prepareEdge(String edgeId, String outVertexId, String inVertexId, String label, Visibility visibility) {
        return prepareEdge(edgeId, outVertexId, inVertexId, label, null, visibility);
    }

    @Override
    public abstract EdgeBuilderByVertexId prepareEdge(String edgeId, String outVertexId, String inVertexId, String label, Long timestamp, Visibility visibility);

    @Override
    public boolean doesEdgeExist(String edgeId, Authorizations authorizations) {
        return getEdge(edgeId, FetchHint.NONE, authorizations) != null;
    }

    @Override
    public Edge getEdge(String edgeId, EnumSet<FetchHint> fetchHints, Authorizations authorizations) {
        return getEdge(edgeId, fetchHints, null, authorizations);
    }

    @Override
    public Edge getEdge(String edgeId, EnumSet<FetchHint> fetchHints, Long endTime, Authorizations authorizations) {
        LOGGER.warn("Performing scan of all edges! Override getEdge.");
        for (Edge edge : getEdges(fetchHints, endTime, authorizations)) {
            if (edge.getId().equals(edgeId)) {
                return edge;
            }
        }
        return null;
    }

    @Override
    public Edge getEdge(String edgeId, Authorizations authorizations) {
        return getEdge(edgeId, FetchHint.ALL, authorizations);
    }

    @Override
    public Map<String, Boolean> doEdgesExist(Iterable<String> ids, Authorizations authorizations) {
        return doEdgesExist(ids, null, authorizations);
    }

    @Override
    public Map<String, Boolean> doEdgesExist(Iterable<String> ids, Long endTime, Authorizations authorizations) {
        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, false);
        }
        for (Edge edge : getEdges(ids, FetchHint.NONE, endTime, authorizations)) {
            results.put(edge.getId(), true);
        }
        return results;
    }

    @Override
    public void deleteVertex(String vertexId, Authorizations authorizations) {
        deleteVertex(getVertex(vertexId, authorizations), authorizations);
    }

    @Override
    public void deleteEdge(String edgeId, Authorizations authorizations) {
        deleteEdge(getEdge(edgeId, authorizations), authorizations);
    }

    @Override
    public void softDeleteVertex(String vertexId, Authorizations authorizations) {
        softDeleteVertex(getVertex(vertexId, authorizations), null, authorizations);
    }

    @Override
    public void softDeleteVertex(String vertexId, Long timestamp, Authorizations authorizations) {
        softDeleteVertex(getVertex(vertexId, authorizations), timestamp, authorizations);
    }

    @Override
    public void softDeleteVertex(Vertex vertex, Authorizations authorizations) {
        softDeleteVertex(vertex, null, authorizations);
    }

    @Override
    public abstract void softDeleteVertex(Vertex vertex, Long timestamp, Authorizations authorizations);

    @Override
    public void softDeleteEdge(String edgeId, Authorizations authorizations) {
        softDeleteEdge(getEdge(edgeId, authorizations), null, authorizations);
    }

    @Override
    public void softDeleteEdge(String edgeId, Long timestamp, Authorizations authorizations) {
        softDeleteEdge(getEdge(edgeId, authorizations), timestamp, authorizations);
    }

    @Override
    public void softDeleteEdge(Edge edge, Authorizations authorizations) {
        softDeleteEdge(edge, null, authorizations);
    }

    @Override
    public abstract void softDeleteEdge(Edge edge, Long timestamp, Authorizations authorizations);

    @Override
    public Iterable<String> filterEdgeIdsByAuthorization(Iterable<String> edgeIds, final String authorizationToMatch, final EnumSet<EdgeFilter> filters, Authorizations authorizations) {
        FilterIterable<Edge> edges = new FilterIterable<Edge>(getEdges(edgeIds, authorizations)) {
            @Override
            protected boolean isIncluded(Edge edge) {
                if (filters.contains(EdgeFilter.EDGE)) {
                    if (edge.getVisibility().hasAuthorization(authorizationToMatch)) {
                        return true;
                    }
                }
                if (filters.contains(EdgeFilter.PROPERTY) || filters.contains(EdgeFilter.PROPERTY_METADATA)) {
                    for (Property property : edge.getProperties()) {
                        if (filters.contains(EdgeFilter.PROPERTY)) {
                            if (property.getVisibility().hasAuthorization(authorizationToMatch)) {
                                return true;
                            }
                        }
                        if (filters.contains(EdgeFilter.PROPERTY_METADATA)) {
                            for (Metadata.Entry entry : property.getMetadata().entrySet()) {
                                if (entry.getVisibility().hasAuthorization(authorizationToMatch)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return false;
            }
        };
        return new ConvertingIterable<Edge, String>(edges) {
            @Override
            protected String convert(Edge edge) {
                return edge.getId();
            }
        };
    }

    @Override
    public Iterable<Edge> getEdges(final Iterable<String> ids, final EnumSet<FetchHint> fetchHints, final Authorizations authorizations) {
        return getEdges(ids, fetchHints, null, authorizations);
    }

    @Override
    public Iterable<Edge> getEdges(final Iterable<String> ids, final EnumSet<FetchHint> fetchHints, final Long endTime, final Authorizations authorizations) {
        LOGGER.warn("Getting each edge one by one! Override getEdges(java.lang.Iterable<java.lang.String>, Authorizations)");
        return new LookAheadIterable<String, Edge>() {
            @Override
            protected boolean isIncluded(String src, Edge edge) {
                return edge != null;
            }

            @Override
            protected Edge convert(String id) {
                return getEdge(id, fetchHints, endTime, authorizations);
            }

            @Override
            protected Iterator<String> createIterator() {
                return ids.iterator();
            }
        };
    }

    @Override
    public Iterable<Edge> getEdges(final Iterable<String> ids, final Authorizations authorizations) {
        return getEdges(ids, FetchHint.ALL, authorizations);
    }

    @Override
    public Iterable<Edge> getEdges(Authorizations authorizations) {
        return getEdges(FetchHint.ALL, authorizations);
    }

    @Override
    public Iterable<Edge> getEdges(EnumSet<FetchHint> fetchHints, Authorizations authorizations) {
        return getEdges(fetchHints, null, authorizations);
    }

    @Override
    public abstract Iterable<Edge> getEdges(EnumSet<FetchHint> fetchHints, Long endTime, Authorizations authorizations);

    @Override
    public Iterable<Path> findPaths(Vertex sourceVertex, Vertex destVertex, int maxHops, Authorizations authorizations) {
        ProgressCallback progressCallback = new ProgressCallback() {
            @Override
            public void progress(double progressPercent, Step step, Integer edgeIndex, Integer vertexCount) {
                LOGGER.debug("findPaths progress %d%%: %s", (int) (progressPercent * 100.0), step.formatMessage(edgeIndex, vertexCount));
            }
        };
        return findPaths(sourceVertex, destVertex, maxHops, progressCallback, authorizations);
    }

    @Override
    public Iterable<Path> findPaths(Vertex sourceVertex, Vertex destVertex, int maxHops, ProgressCallback progressCallback, Authorizations authorizations) {
        return pathFindingAlgorithm.findPaths(this, sourceVertex, destVertex, maxHops, progressCallback, authorizations);
    }

    @Override
    public Iterable<Path> findPaths(String sourceVertexId, String destVertexId, int maxHops, ProgressCallback progressCallback, Authorizations authorizations) {
        EnumSet<FetchHint> fetchHints = FetchHint.EDGE_REFS;
        Vertex sourceVertex = getVertex(sourceVertexId, fetchHints, authorizations);
        if (sourceVertex == null) {
            throw new IllegalArgumentException("Could not find vertex with id: " + sourceVertexId);
        }
        Vertex destVertex = getVertex(destVertexId, fetchHints, authorizations);
        if (destVertex == null) {
            throw new IllegalArgumentException("Could not find vertex with id: " + destVertexId);
        }
        return findPaths(sourceVertex, destVertex, maxHops, progressCallback, authorizations);
    }

    @Override
    public Iterable<Path> findPaths(String sourceVertexId, String destVertexId, int maxHops, Authorizations authorizations) {
        EnumSet<FetchHint> fetchHints = FetchHint.EDGE_REFS;
        Vertex sourceVertex = getVertex(sourceVertexId, fetchHints, authorizations);
        if (sourceVertex == null) {
            throw new IllegalArgumentException("Could not find vertex with id: " + sourceVertexId);
        }
        Vertex destVertex = getVertex(destVertexId, fetchHints, authorizations);
        if (destVertex == null) {
            throw new IllegalArgumentException("Could not find vertex with id: " + destVertexId);
        }
        return findPaths(sourceVertex, destVertex, maxHops, authorizations);
    }

    @Override
    @Deprecated
    public Iterable<String> findRelatedEdges(Iterable<String> vertexIds, Authorizations authorizations) {
        return findRelatedEdgeIds(vertexIds, authorizations);
    }

    @Override
    @Deprecated
    public Iterable<String> findRelatedEdges(Iterable<String> vertexIds, Long endTime, Authorizations authorizations) {
        return findRelatedEdgeIds(vertexIds, endTime, authorizations);
    }

    @Override
    public Iterable<String> findRelatedEdgeIds(Iterable<String> vertexIds, Authorizations authorizations) {
        return findRelatedEdgeIds(vertexIds, null, authorizations);
    }

    @Override
    public Iterable<String> findRelatedEdgeIds(Iterable<String> vertexIds, Long endTime, Authorizations authorizations) {
        Iterable<RelatedEdge> relatedEdges = findRelatedEdgeSummary(vertexIds, endTime, authorizations);
        return new ConvertingIterable<RelatedEdge, String>(relatedEdges) {
            @Override
            protected String convert(RelatedEdge o) {
                return o.getEdgeId();
            }
        };
    }

    @Override
    public Iterable<RelatedEdge> findRelatedEdgeSummary(Iterable<String> vertexIds, Authorizations authorizations) {
        return findRelatedEdgeSummary(vertexIds, null, authorizations);
    }

    @Override
    public Iterable<RelatedEdge> findRelatedEdgeSummary(Iterable<String> vertexIds, Long endTime, Authorizations authorizations) {
        List<RelatedEdge> results = new ArrayList<>();
        List<Vertex> vertices = IterableUtils.toList(getVertices(vertexIds, authorizations));

        for (Vertex outVertex : vertices) {
            for (Vertex inVertex : vertices) {
                Iterable<EdgeInfo> edgeInfos = outVertex.getEdgeInfos(Direction.OUT, authorizations);
                for (EdgeInfo edgeInfo : edgeInfos) {
                    if (edgeInfo.getVertexId().equals(inVertex.getId())) {
                        results.add(new RelatedEdgeImpl(edgeInfo.getEdgeId(), edgeInfo.getLabel(), outVertex.getId(), inVertex.getId()));
                    }
                }
            }
        }
        return results;
    }

    protected abstract GraphMetadataStore getGraphMetadataStore();

    @Override
    public final Iterable<GraphMetadataEntry> getMetadata() {
        return getGraphMetadataStore().getMetadata();
    }

    @Override
    public final void setMetadata(String key, Object value) {
        getGraphMetadataStore().setMetadata(key, value);
    }

    @Override
    public final Object getMetadata(String key) {
        return getGraphMetadataStore().getMetadata(key);
    }

    @Override
    public final Iterable<GraphMetadataEntry> getMetadataWithPrefix(String prefix) {
        return getGraphMetadataStore().getMetadataWithPrefix(prefix);
    }

    @Override
    public abstract GraphQuery query(Authorizations authorizations);

    @Override
    public abstract GraphQuery query(String queryString, Authorizations authorizations);

    @Override
    public abstract void reindex(Authorizations authorizations);

    @Override
    public abstract void flush();

    @Override
    public abstract void shutdown();

    @Override
    public abstract DefinePropertyBuilder defineProperty(String propertyName);

    @Override
    public abstract boolean isFieldBoostSupported();

    @Override
    public abstract SearchIndexSecurityGranularity getSearchIndexSecurityGranularity();

    @Override
    public void addGraphEventListener(GraphEventListener graphEventListener) {
        this.graphEventListeners.add(graphEventListener);
    }

    protected boolean hasEventListeners() {
        return this.graphEventListeners.size() > 0;
    }

    protected void fireGraphEvent(GraphEvent graphEvent) {
        for (GraphEventListener graphEventListener : this.graphEventListeners) {
            graphEventListener.onGraphEvent(graphEvent);
        }
    }

    @Override
    public boolean isQuerySimilarToTextSupported() {
        return false;
    }

    @Override
    public SimilarToGraphQuery querySimilarTo(String[] fields, String text, Authorizations authorizations) {
        throw new VertexiumException("querySimilarTo not supported");
    }

    @Override
    public Authorizations createAuthorizations(Collection<String> auths) {
        return createAuthorizations(auths.toArray(new String[auths.size()]));
    }

    @Override
    public Authorizations createAuthorizations(Authorizations auths, String... additionalAuthorizations) {
        Set<String> newAuths = new HashSet<>();
        Collections.addAll(newAuths, auths.getAuthorizations());
        Collections.addAll(newAuths, additionalAuthorizations);
        return createAuthorizations(newAuths);
    }

    @Override
    public Authorizations createAuthorizations(Authorizations auths, Collection<String> additionalAuthorizations) {
        return createAuthorizations(auths, additionalAuthorizations.toArray(new String[additionalAuthorizations.size()]));
    }

    @Override
    public Map<Object, Long> getVertexPropertyCountByValue(String propertyName, Authorizations authorizations) {
        Map<Object, Long> countsByValue = new HashMap<>();
        for (Vertex v : getVertices(authorizations)) {
            for (Property p : v.getProperties()) {
                if (propertyName.equals(p.getName())) {
                    Object mapKey = p.getValue();
                    if (mapKey instanceof String) {
                        mapKey = ((String) mapKey).toLowerCase();
                    }
                    Long currentValue = countsByValue.get(mapKey);
                    if (currentValue == null) {
                        countsByValue.put(mapKey, 1L);
                    } else {
                        countsByValue.put(mapKey, currentValue + 1);
                    }
                }
            }
        }
        return countsByValue;
    }

    @Override
    public long getVertexCount(Authorizations authorizations) {
        return count(getVertices(authorizations));
    }

    @Override
    public long getEdgeCount(Authorizations authorizations) {
        return count(getEdges(authorizations));
    }

    @Override
    public abstract void deleteVertex(Vertex vertex, Authorizations authorizations);

    @Override
    public abstract void deleteEdge(Edge edge, Authorizations authorizations);

    @Override
    public abstract MultiVertexQuery query(String[] vertexIds, String queryString, Authorizations authorizations);

    @Override
    public abstract MultiVertexQuery query(String[] vertexIds, Authorizations authorizations);

    @Override
    public abstract IdGenerator getIdGenerator();

    @Override
    public abstract boolean isVisibilityValid(Visibility visibility, Authorizations authorizations);

    @Override
    public abstract void clearData();

    @Override
    public abstract void markVertexHidden(Vertex vertex, Visibility visibility, Authorizations authorizations);

    @Override
    public abstract void markVertexVisible(Vertex vertex, Visibility visibility, Authorizations authorizations);

    @Override
    public abstract void markEdgeHidden(Edge edge, Visibility visibility, Authorizations authorizations);

    @Override
    public abstract void markEdgeVisible(Edge edge, Visibility visibility, Authorizations authorizations);

    @Override
    public abstract Authorizations createAuthorizations(String... auths);
}
