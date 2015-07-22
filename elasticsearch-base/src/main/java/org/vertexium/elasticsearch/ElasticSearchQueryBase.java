package org.vertexium.elasticsearch;

import com.google.common.base.Joiner;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.*;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoHashGridBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.HistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.vertexium.*;
import org.vertexium.elasticsearch.score.ScoringStrategy;
import org.vertexium.query.*;
import org.vertexium.type.GeoCircle;
import org.vertexium.util.*;

import java.io.IOException;
import java.util.*;

public abstract class ElasticSearchQueryBase extends QueryBase {
    private static final VertexiumLogger LOGGER = VertexiumLoggerFactory.getLogger(ElasticSearchQueryBase.class);
    public static final VertexiumLogger QUERY_LOGGER = VertexiumLoggerFactory.getQueryLogger(Query.class);
    private final TransportClient client;
    private final boolean evaluateHasContainers;
    private final boolean evaluateQueryString;
    private final StandardAnalyzer analyzer;
    private final ScoringStrategy scoringStrategy;
    private final IndexSelectionStrategy indexSelectionStrategy;

    protected ElasticSearchQueryBase(
            TransportClient client,
            Graph graph,
            String queryString,
            Map<String, PropertyDefinition> propertyDefinitions,
            ScoringStrategy scoringStrategy,
            IndexSelectionStrategy indexSelectionStrategy,
            boolean evaluateQueryString,
            boolean evaluateHasContainers,
            Authorizations authorizations) {
        super(graph, queryString, propertyDefinitions, authorizations);
        this.client = client;
        this.evaluateQueryString = evaluateQueryString;
        this.evaluateHasContainers = evaluateHasContainers;
        this.scoringStrategy = scoringStrategy;
        this.analyzer = new StandardAnalyzer();
        this.indexSelectionStrategy = indexSelectionStrategy;
    }

    protected ElasticSearchQueryBase(
            TransportClient client,
            Graph graph,
            String[] similarToFields,
            String similarToText,
            Map<String, PropertyDefinition> propertyDefinitions,
            ScoringStrategy scoringStrategy,
            IndexSelectionStrategy indexSelectionStrategy,
            boolean evaluateQueryString,
            boolean evaluateHasContainers,
            Authorizations authorizations) {
        super(graph, similarToFields, similarToText, propertyDefinitions, authorizations);
        this.client = client;
        this.evaluateQueryString = evaluateQueryString;
        this.evaluateHasContainers = evaluateHasContainers;
        this.scoringStrategy = scoringStrategy;
        this.analyzer = new StandardAnalyzer();
        this.indexSelectionStrategy = indexSelectionStrategy;
    }

    @Override
    public Iterable<Vertex> vertices(EnumSet<FetchHint> fetchHints) {
        long startTime = System.nanoTime();
        SearchResponse response;
        try {
            response = getSearchResponse(ElasticSearchElementType.VERTEX);
        } catch (IndexMissingException ex) {
            LOGGER.debug("Index missing: %s", ex.getMessage());
            return new ArrayList<>();
        } catch (VertexiumNoMatchingPropertiesException ex) {
            LOGGER.debug("Could not find property", ex);
            return new ArrayList<>();
        }
        final SearchHits hits = response.getHits();
        List<String> ids = IterableUtils.toList(new ConvertingIterable<SearchHit, String>(hits) {
            @Override
            protected String convert(SearchHit searchHit) {
                return searchHit.getId();
            }
        });
        long endTime = System.nanoTime();
        long searchTime = endTime - startTime;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("elastic search results %d of %d (time: %dms)", ids.size(), hits.getTotalHits(), searchTime / 1000 / 1000);
        }

        // since ES doesn't support security we will rely on the graph to provide vertex filtering
        // and rely on the DefaultGraphQueryIterable to provide property filtering
        QueryParameters filterParameters = getParameters().clone();
        filterParameters.setSkip(0); // ES already did a skip
        Iterable<Vertex> vertices = getGraph().getVertices(ids, fetchHints, filterParameters.getAuthorizations());
        return createIterable(response, filterParameters, vertices, evaluateQueryString, evaluateHasContainers, searchTime, hits);
    }

    @Override
    public Iterable<Edge> edges(EnumSet<FetchHint> fetchHints) {
        long startTime = System.nanoTime();
        SearchResponse response;
        try {
            response = getSearchResponse(ElasticSearchElementType.EDGE);
        } catch (IndexMissingException ex) {
            LOGGER.debug("Index missing: %s", ex.getMessage());
            return new ArrayList<>();
        } catch (VertexiumNoMatchingPropertiesException ex) {
            LOGGER.debug("Could not find property", ex);
            return new ArrayList<>();
        }
        final SearchHits hits = response.getHits();
        List<String> ids = IterableUtils.toList(new ConvertingIterable<SearchHit, String>(hits) {
            @Override
            protected String convert(SearchHit searchHit) {
                return searchHit.getId();
            }
        });
        long endTime = System.nanoTime();
        long searchTime = endTime - startTime;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("elastic search results %d of %d (time: %dms)", ids.size(), hits.getTotalHits(), (endTime - startTime) / 1000 / 1000);
        }

        // since ES doesn't support security we will rely on the graph to provide edge filtering
        // and rely on the DefaultGraphQueryIterable to provide property filtering
        QueryParameters filterParameters = getParameters().clone();
        filterParameters.setSkip(0); // ES already did a skip
        Iterable<Edge> edges = getGraph().getEdges(ids, fetchHints, filterParameters.getAuthorizations());
        // TODO instead of passing false here to not evaluate the query string it would be better to support the Lucene query
        return createIterable(response, filterParameters, edges, evaluateQueryString, evaluateHasContainers, searchTime, hits);
    }

    @Override
    public Iterable<Element> elements(EnumSet<FetchHint> fetchHints) {
        long startTime = System.nanoTime();
        SearchResponse response;
        try {
            response = getSearchResponse(null);
        } catch (IndexMissingException ex) {
            LOGGER.debug("Index missing: %s", ex.getMessage());
            return new ArrayList<>();
        } catch (VertexiumNoMatchingPropertiesException ex) {
            LOGGER.debug("Could not find property", ex);
            return new ArrayList<>();
        }
        final SearchHits hits = response.getHits();
        List<String> vertexIds = new ArrayList<>();
        List<String> edgeIds = new ArrayList<>();
        for (SearchHit hit : hits) {
            SearchHitField elementType = hit.getFields().get(ElasticSearchSearchIndexBase.ELEMENT_TYPE_FIELD_NAME);
            if (elementType == null) {
                continue;
            }
            ElasticSearchElementType et = ElasticSearchElementType.parse(elementType.getValue().toString());
            switch (et) {
                case VERTEX:
                    vertexIds.add(hit.getId());
                    break;
                case EDGE:
                    edgeIds.add(hit.getId());
                    break;
                default:
                    LOGGER.warn("Unhandled element type returned: %s", elementType);
                    break;
            }
        }
        long endTime = System.nanoTime();
        long searchTime = endTime - startTime;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "elastic search results (vertices: %d, edges: %d = %d) of %d (time: %dms)",
                    vertexIds.size(),
                    edgeIds.size(),
                    vertexIds.size() + edgeIds.size(),
                    hits.getTotalHits(),
                    (endTime - startTime) / 1000 / 1000);
        }

        // since ES doesn't support security we will rely on the graph to provide edge filtering
        // and rely on the DefaultGraphQueryIterable to provide property filtering
        QueryParameters filterParameters = getParameters().clone();
        filterParameters.setSkip(0); // ES already did a skip
        Iterable<Vertex> vertices = getGraph().getVertices(vertexIds, fetchHints, filterParameters.getAuthorizations());
        Iterable<Edge> edges = getGraph().getEdges(edgeIds, fetchHints, filterParameters.getAuthorizations());
        Iterable<Element> elements = new JoinIterable<>(new ToElementIterable<>(vertices), new ToElementIterable<>(edges));
        // TODO instead of passing false here to not evaluate the query string it would be better to support the Lucene query
        return createIterable(response, filterParameters, elements, evaluateQueryString, evaluateHasContainers, searchTime, hits);
    }

    protected <T extends Element> ElasticSearchGraphQueryIterable<T> createIterable(
            SearchResponse response,
            QueryParameters filterParameters,
            Iterable<T> elements,
            boolean evaluateQueryString,
            boolean evaluateHasContainers,
            long searchTime,
            SearchHits hits
    ) {
        return new ElasticSearchGraphQueryIterable<>(
                this,
                response,
                filterParameters,
                elements,
                evaluateQueryString,
                evaluateHasContainers,
                hits.getTotalHits(),
                searchTime,
                hits
        );
    }

    private SearchResponse getSearchResponse(ElasticSearchElementType elementType) {
        List<FilterBuilder> filters = getFilters(elementType);
        QueryBuilder query = createQuery(getParameters(), elementType, filters);
        query = scoringStrategy.updateQuery(query);
        SearchRequestBuilder q = getSearchRequestBuilder(filters, query, elementType);

        if (QUERY_LOGGER.isTraceEnabled()) {
            QUERY_LOGGER.trace("query: %s", q);
        }
        return q.execute()
                .actionGet();
    }

    protected List<FilterBuilder> getFilters(ElasticSearchElementType elementType) {
        List<FilterBuilder> filters = new ArrayList<>();
        if (elementType != null) {
            addElementTypeFilter(filters, elementType);
        }
        for (HasContainer has : getParameters().getHasContainers()) {
            if (has instanceof HasValueContainer) {
                filters.add(getFiltersForHasValueContainer((HasValueContainer) has));
            } else if (has instanceof HasPropertyContainer) {
                filters.add(getFilterForHasPropertyContainer((HasPropertyContainer) has));
            } else if (has instanceof HasNotPropertyContainer) {
                filters.add(getFilterForHasNotPropertyContainer((HasNotPropertyContainer) has));
            } else {
                throw new VertexiumException("Unexpected type " + has.getClass().getName());
            }
        }
        return filters;
    }

    protected FilterBuilder getFilterForHasNotPropertyContainer(HasNotPropertyContainer hasNotProperty) {
        String[] propertyNames = getPropertyNames(hasNotProperty.getKey());
        List<FilterBuilder> filters = new ArrayList<>();
        for (String propertyName : propertyNames) {
            filters.add(FilterBuilders.notFilter(FilterBuilders.existsFilter(propertyName)));
        }
        return getSingleFilterOrOrTheFilters(filters);
    }

    protected FilterBuilder getFilterForHasPropertyContainer(HasPropertyContainer hasProperty) {
        String[] propertyNames = getPropertyNames(hasProperty.getKey());
        List<FilterBuilder> filters = new ArrayList<>();
        for (String propertyName : propertyNames) {
            filters.add(FilterBuilders.existsFilter(propertyName));
        }
        return getSingleFilterOrOrTheFilters(filters);
    }

    protected FilterBuilder getFiltersForHasValueContainer(HasValueContainer has) {
        if (has.predicate instanceof Compare) {
            return getFilterForComparePredicate((Compare) has.predicate, has);
        } else if (has.predicate instanceof Contains) {
            return getFilterForContainsPredicate((Contains) has.predicate, has);
        } else if (has.predicate instanceof TextPredicate) {
            return getFilterForTextPredicate((TextPredicate) has.predicate, has);
        } else if (has.predicate instanceof GeoCompare) {
            return getFilterForGeoComparePredicate((GeoCompare) has.predicate, has);
        } else {
            throw new VertexiumException("Unexpected predicate type " + has.predicate.getClass().getName());
        }
    }

    protected FilterBuilder getFilterForGeoComparePredicate(GeoCompare compare, HasValueContainer has) {
        String[] keys = getPropertyNames(has.key);
        List<FilterBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            String propertyName = key + ElasticSearchSearchIndexBase.GEO_PROPERTY_NAME_SUFFIX;
            switch (compare) {
                case WITHIN:
                    if (has.value instanceof GeoCircle) {
                        GeoCircle geoCircle = (GeoCircle) has.value;
                        double lat = geoCircle.getLatitude();
                        double lon = geoCircle.getLongitude();
                        double distance = geoCircle.getRadius();

                        PropertyDefinition propertyDefinition = this.getPropertyDefinitions().get(propertyName);
                        if (propertyDefinition != null && propertyDefinition.getDataType() == GeoCircle.class) {
                            ShapeBuilder shapeBuilder = ShapeBuilder.newCircleBuilder()
                                    .center(lon, lat)
                                    .radius(distance, DistanceUnit.KILOMETERS);
                            filters
                                    .add(new GeoShapeFilterBuilder(propertyName, shapeBuilder));
                        } else {
                            filters
                                    .add(FilterBuilders
                                            .geoDistanceFilter(propertyName)
                                            .point(lat, lon)
                                            .distance(distance, DistanceUnit.KILOMETERS));
                        }
                    } else {
                        throw new VertexiumException("Unexpected has value type " + has.value.getClass().getName());
                    }
                    break;
                default:
                    throw new VertexiumException("Unexpected GeoCompare predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters);
    }

    private FilterBuilder getSingleFilterOrOrTheFilters(List<FilterBuilder> filters) {
        if (filters.size() > 1) {
            return FilterBuilders.orFilter(filters.toArray(new FilterBuilder[filters.size()]));
        } else if (filters.size() == 1) {
            return filters.get(0);
        } else {
            throw new VertexiumException("Unexpected filter count, expected at least 1 filter");
        }
    }

    protected FilterBuilder getFilterForTextPredicate(TextPredicate compare, HasValueContainer has) {
        Object value = has.value;
        String[] keys = getPropertyNames(has.key);
        List<FilterBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            if (value instanceof String) {
                value = ((String) value).toLowerCase(); // using the standard analyzer all strings are lower-cased.
            }
            switch (compare) {
                case CONTAINS:
                    if (value instanceof String) {
                        filters.add(FilterBuilders.termsFilter(key, splitStringIntoTerms((String) value)).execution("and"));
                    } else {
                        filters.add(FilterBuilders.termFilter(key, value));
                    }
                    break;
                default:
                    throw new VertexiumException("Unexpected text predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters);
    }

    protected FilterBuilder getFilterForContainsPredicate(Contains contains, HasValueContainer has) {
        Object value = has.value;
        String[] keys = getPropertyNames(has.key);
        List<FilterBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            if (value instanceof String || value instanceof String[]) {
                key = key + ElasticSearchSearchIndexBase.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
            }
            if (has.value instanceof Iterable) {
                has.value = IterableUtils.toArray((Iterable<?>) has.value, Object.class);
            }
            switch (contains) {
                case IN:
                    filters.add(FilterBuilders.inFilter(key, (Object[]) has.value));
                    break;
                case NOT_IN:
                    filters.add(FilterBuilders.notFilter(FilterBuilders.inFilter(key, (Object[]) has.value)));
                    break;
                default:
                    throw new VertexiumException("Unexpected Contains predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters);
    }

    protected FilterBuilder getFilterForComparePredicate(Compare compare, HasValueContainer has) {
        Object value = has.value;
        String[] keys = getPropertyNames(has.key);
        List<FilterBuilder> filters = new ArrayList<>();
        for (String key : keys) {
            if (value instanceof String || value instanceof String[]) {
                key = key + ElasticSearchSearchIndexBase.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
            }
            switch (compare) {
                case EQUAL:
                    if (value instanceof DateOnly) {
                        DateOnly dateOnlyValue = ((DateOnly) value);
                        filters.add(FilterBuilders.rangeFilter(key).from(dateOnlyValue.toString()).to(dateOnlyValue.toString()));
                    } else {
                        filters.add(FilterBuilders.termFilter(key, value));
                    }
                    break;
                case GREATER_THAN_EQUAL:
                    filters.add(FilterBuilders.rangeFilter(key).gte(value));
                    break;
                case GREATER_THAN:
                    filters.add(FilterBuilders.rangeFilter(key).gt(value));
                    break;
                case LESS_THAN_EQUAL:
                    filters.add(FilterBuilders.rangeFilter(key).lte(value));
                    break;
                case LESS_THAN:
                    filters.add(FilterBuilders.rangeFilter(key).lt(value));
                    break;
                case NOT_EQUAL:
                    addNotFilter(filters, key, value);
                    break;
                default:
                    throw new VertexiumException("Unexpected Compare predicate " + has.predicate);
            }
        }
        return getSingleFilterOrOrTheFilters(filters);
    }

    private String[] getPropertyNames(String propertyName) {
        return getSearchIndex().getAllMatchingPropertyNames(getGraph(), propertyName, getParameters().getAuthorizations());
    }

    private ElasticSearchSearchIndexBase getSearchIndex() {
        return (ElasticSearchSearchIndexBase) ((GraphBaseWithSearchIndex) getGraph()).getSearchIndex();
    }

    protected void addElementTypeFilter(List<FilterBuilder> filters, ElasticSearchElementType elementType) {
        if (elementType != null) {
            filters.add(createElementTypeFilter(elementType));
        }
    }

    protected TermsFilterBuilder createElementTypeFilter(ElasticSearchElementType elementType) {
        return FilterBuilders.inFilter(ElasticSearchSearchIndexBase.ELEMENT_TYPE_FIELD_NAME, elementType.getKey());
    }

    protected void addNotFilter(List<FilterBuilder> filters, String key, Object value) {
        filters.add(FilterBuilders.notFilter(FilterBuilders.inFilter(key, value)));
    }

    protected SearchRequestBuilder getSearchRequestBuilder(List<FilterBuilder> filters, QueryBuilder queryBuilder, ElasticSearchElementType elementType) {
        AndFilterBuilder filterBuilder = getFilterBuilder(filters);
        String[] indicesToQuery = getIndexSelectionStrategy().getIndicesToQuery(this, elementType);
        if (QUERY_LOGGER.isTraceEnabled()) {
            QUERY_LOGGER.trace("indicesToQuery: %s", Joiner.on(", ").join(indicesToQuery));
        }
        return getClient()
                .prepareSearch(indicesToQuery)
                .setTypes(ElasticSearchSearchIndexBase.ELEMENT_TYPE)
                .setQuery(QueryBuilders.filteredQuery(queryBuilder, filterBuilder))
                .addField(ElasticSearchSearchIndexBase.ELEMENT_TYPE_FIELD_NAME)
                .setFrom((int) getParameters().getSkip())
                .setSize((int) getParameters().getLimit());
    }

    protected AndFilterBuilder getFilterBuilder(List<FilterBuilder> filters) {
        return FilterBuilders.andFilter(filters.toArray(new FilterBuilder[filters.size()]));
    }

    private String[] splitStringIntoTerms(String value) {
        try {
            List<String> results = new ArrayList<>();
            try (TokenStream tokens = analyzer.tokenStream("", value)) {
                CharTermAttribute term = tokens.getAttribute(CharTermAttribute.class);
                tokens.reset();
                while (tokens.incrementToken()) {
                    String t = term.toString().trim();
                    if (t.length() > 0) {
                        results.add(t);
                    }
                }
            }
            return results.toArray(new String[results.size()]);
        } catch (IOException e) {
            throw new VertexiumException("Could not tokenize string: " + value, e);
        }
    }

    protected QueryBuilder createQuery(QueryParameters queryParameters, ElasticSearchElementType elementType, List<FilterBuilder> filters) {
        if (queryParameters instanceof QueryStringQueryParameters) {
            String queryString = ((QueryStringQueryParameters) queryParameters).getQueryString();
            if (queryString == null || queryString.equals("*")) {
                return QueryBuilders.matchAllQuery();
            }
            return QueryBuilders.queryString(queryString);
        } else if (queryParameters instanceof SimilarToTextQueryParameters) {
            SimilarToTextQueryParameters similarTo = (SimilarToTextQueryParameters) queryParameters;
            List<String> allFields = new ArrayList<>();
            String[] fields = similarTo.getFields();
            for (String field : fields) {
                Collections.addAll(allFields, getPropertyNames(field));
            }
            MoreLikeThisQueryBuilder q = QueryBuilders.moreLikeThisQuery(allFields.toArray(new String[allFields.size()]))
                    .likeText(similarTo.getText());
            if (similarTo.getPercentTermsToMatch() != null) {
                q.percentTermsToMatch(similarTo.getPercentTermsToMatch());
            }
            if (similarTo.getMinTermFrequency() != null) {
                q.minTermFreq(similarTo.getMinTermFrequency());
            }
            if (similarTo.getMaxQueryTerms() != null) {
                q.maxQueryTerms(similarTo.getMaxQueryTerms());
            }
            if (similarTo.getMinDocFrequency() != null) {
                q.minDocFreq(similarTo.getMinDocFrequency());
            }
            if (similarTo.getMaxDocFrequency() != null) {
                q.maxDocFreq(similarTo.getMaxDocFrequency());
            }
            if (similarTo.getBoost() != null) {
                q.boost(similarTo.getBoost());
            }
            return q;
        } else {
            throw new VertexiumException("Query parameters not supported of type: " + queryParameters.getClass().getName());
        }
    }

    public TransportClient getClient() {
        return client;
    }

    protected void addGeohashQueryToSearchRequestBuilder(SearchRequestBuilder searchRequestBuilder, List<GeohashQueryItem> geohashQueryItems) {
        for (GeohashQueryItem geohashQueryItem : geohashQueryItems) {
            for (String propertyName : getPropertyNames(geohashQueryItem.getFieldName())) {
                String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
                String aggName = createAggregationName(geohashQueryItem.getAggregationName(), visibilityHash);
                GeoHashGridBuilder agg = AggregationBuilders.geohashGrid(aggName);
                agg.field(propertyName + ElasticSearchSearchIndexBase.GEO_PROPERTY_NAME_SUFFIX);
                agg.precision(geohashQueryItem.getPrecision());
                searchRequestBuilder.addAggregation(agg);
            }
        }
    }

    private String createAggregationName(String aggName, String visibilityHash) {
        if (visibilityHash != null && visibilityHash.length() > 0) {
            return aggName + "_" + visibilityHash;
        }
        return aggName;
    }

    protected void addTermsQueryToSearchRequestBuilder(SearchRequestBuilder searchRequestBuilder, List<TermsQueryItem> termsQueryItems) {
        for (TermsQueryItem termsQueryItem : termsQueryItems) {
            String fieldName = termsQueryItem.getFieldName();
            PropertyDefinition propertyDefinition = getPropertyDefinition(fieldName);
            for (String propertyName : getPropertyNames(fieldName)) {
                if (propertyDefinition != null && propertyDefinition.getTextIndexHints().contains(TextIndexHint.EXACT_MATCH)) {
                    propertyName = propertyName + ElasticSearchSearchIndexBase.EXACT_MATCH_PROPERTY_NAME_SUFFIX;
                }

                String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
                TermsBuilder agg = AggregationBuilders.terms(createAggregationName(termsQueryItem.getAggregationName(), visibilityHash));
                agg.field(propertyName);
                searchRequestBuilder.addAggregation(agg);
            }
        }
    }

    protected void addHistogramQueryToSearchRequestBuilder(SearchRequestBuilder searchRequestBuilder, List<HistogramQueryItem> histogramQueryItems) {
        for (HistogramQueryItem histogramQueryItem : histogramQueryItems) {
            PropertyDefinition propertyDefinition = getPropertyDefinition(histogramQueryItem.getFieldName());
            if (propertyDefinition == null) {
                throw new VertexiumException("Could not find mapping for property: " + histogramQueryItem.getFieldName());
            }
            Class propertyDataType = propertyDefinition.getDataType();
            for (String propertyName : getPropertyNames(histogramQueryItem.getFieldName())) {
                String visibilityHash = getSearchIndex().getPropertyVisibilityHashFromDeflatedPropertyName(propertyName);
                String aggName = createAggregationName(histogramQueryItem.getAggregationName(), visibilityHash);
                if (propertyDataType == Date.class) {
                    DateHistogramBuilder agg = AggregationBuilders.dateHistogram(aggName);
                    agg.field(propertyName);
                    agg.interval(Long.parseLong(histogramQueryItem.getInterval()));
                    searchRequestBuilder.addAggregation(agg);
                } else {
                    HistogramBuilder agg = AggregationBuilders.histogram(aggName);
                    agg.field(propertyName);
                    agg.interval(Long.parseLong(histogramQueryItem.getInterval()));
                    searchRequestBuilder.addAggregation(agg);
                }
            }
        }
    }

    private PropertyDefinition getPropertyDefinition(String propertyName) {
        return getSearchIndex().getPropertyDefinition(getGraph(), propertyName);
    }

    protected IndexSelectionStrategy getIndexSelectionStrategy() {
        return indexSelectionStrategy;
    }

    public String getAggregationName(String name) {
        return getSearchIndex().getAggregationName(name);
    }
}
