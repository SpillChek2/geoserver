/* (c) 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.catalog.rest;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.impl.AttributeTypeInfoImpl;
import org.geoserver.catalog.rest.FormatCollectionWrapper.JSONCollectionWrapper;
import org.geoserver.catalog.rest.FormatCollectionWrapper.XMLCollectionWrapper;
import org.geoserver.config.util.XStreamPersister;
import org.geoserver.feature.RetypingFeatureCollection;
import org.geoserver.rest.ResourceNotFoundException;
import org.geoserver.rest.RestBaseController;
import org.geoserver.rest.RestException;
import org.geoserver.rest.converters.XStreamMessageConverter;
import org.geoserver.rest.wrapper.RestWrapper;
import org.geotools.coverage.grid.io.GranuleSource;
import org.geotools.coverage.grid.io.GranuleStore;
import org.geotools.coverage.grid.io.StructuredGridCoverage2DReader;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureTypes;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.reflection.ReflectionConverter;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

/**
 * Structured coverage controller, allows to visit and query granules
 */
@RestController
@ControllerAdvice
@RequestMapping(path = RestBaseController.ROOT_PATH
        + "/workspaces/{workspaceName}/coveragestores/{storeName}/coverages/{coverageName}/index")
public class StructuredCoverageController extends CatalogController {

    private static final Logger LOGGER = Logging.getLogger(StructuredCoverageController.class);

    static final FilterFactory2 FF = CommonFactoryFinder.getFilterFactory2();

    /**
     * Just holds a list of attributes
     * 
     * @author Andrea Aime - GeoSolutions
     */
    static class IndexSchema {
        List<AttributeTypeInfo> attributes;

        public IndexSchema(List<AttributeTypeInfo> attributes) {
            this.attributes = attributes;
        }
    }

    @Autowired
    public StructuredCoverageController(Catalog catalog) {
        super(catalog);
    }

    @GetMapping(produces = { MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public RestWrapper<IndexSchema> getIndex(
            @PathVariable(name = "workspaceName") String workspaceName,
            @PathVariable(name = "storeName") String storeName,
            @PathVariable(name = "coverageName") String coverageName) throws IOException {
        GranuleSource source = getGranuleSource(workspaceName, storeName, coverageName);
        SimpleFeatureType schema = source.getSchema();
        List<AttributeTypeInfo> attributes = new CatalogBuilder(catalog).getAttributes(schema,
                null);

        IndexSchema indexSchema = new IndexSchema(attributes);
        return wrapObject(indexSchema, IndexSchema.class);
    }

    @GetMapping(path = "/granules", produces = { MediaType.APPLICATION_XML_VALUE,
            MediaType.APPLICATION_JSON_VALUE })
    @ResponseBody
    public SimpleFeatureCollection getGranules(
            @PathVariable(name = "workspaceName") String workspaceName,
            @PathVariable(name = "storeName") String storeName,
            @PathVariable(name = "coverageName") String coverageName,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) throws IOException {

        GranuleSource source = getGranuleSource(workspaceName, storeName, coverageName);
        Query q = toQuery(filter, offset, limit);

        LOGGER.log(Level.SEVERE, "Still need to parse the filters");

        return forceNonNullNamespace(source.getGranules(q));
    }

    @DeleteMapping(path = "/granules")
    @ResponseBody
    public void deleteGranules(@PathVariable(name = "workspaceName") String workspaceName,
            @PathVariable(name = "storeName") String storeName,
            @PathVariable(name = "coverageName") String coverageName,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "limit", required = false) Integer limit) throws IOException {
        GranuleStore store = getGranuleStore(workspaceName, storeName, coverageName);
        Query q = toQuery(filter, offset, limit);

        LOGGER.log(Level.SEVERE, "Still need to parse the filters");

        store.removeGranules(q.getFilter());
    }

    /*
     * Note, the .+ regular expression allows granuleId to contain dots instead of having them
     * interpreted as format extension
     */
    @GetMapping(path = "/granules/{granuleId:.+}", produces = { MediaType.APPLICATION_XML_VALUE,
            MediaType.APPLICATION_JSON_VALUE })
    @ResponseBody
    public FormatCollectionWrapper getGranule(
            @PathVariable(name = "workspaceName") String workspaceName,
            @PathVariable(name = "storeName") String storeName,
            @PathVariable(name = "coverageName") String coverageName,
            @PathVariable(name = "granuleId") String granuleId) throws IOException {
        GranuleSource source = getGranuleSource(workspaceName, storeName, coverageName);
        Filter filter = getGranuleIdFilter(granuleId);
        Query q = new Query(null, filter);

        SimpleFeatureCollection granules = source.getGranules(q);
        if (granules.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Could not find a granule with id " + granuleId + " in coverage " + coverageName);
        }

        SimpleFeatureCollection collection = forceNonNullNamespace(granules);
        
        // and now for the fun part, figure out the extension if it was there, and force
        // the right output format... ugly as hell, but we could not find a better solution
        // regexes with positive and negative lookaheads were tried
        if(granuleId.endsWith(".json")) {
            return new JSONCollectionWrapper(collection);
        } else {
            return new XMLCollectionWrapper(collection);
        }
    }

    private Filter getGranuleIdFilter(String granuleId) {
        if(granuleId.endsWith(".xml")) {
            granuleId = granuleId.substring(0, granuleId.length() - 4);
        } else if(granuleId.endsWith(".json")) {
            granuleId = granuleId.substring(0, granuleId.length() - 5);
        }
        Filter filter = FF.id(FF.featureId(granuleId));
        return filter;
    }
    
    /*
     * Note, the .+ regular expression allows granuleId to contain dots instead of having them
     * interpreted as format extension
     */
    @DeleteMapping(path = "/granules/{granuleId:.+}")
    @ResponseBody
    public void deleteGranule(@PathVariable(name = "workspaceName") String workspaceName,
            @PathVariable(name = "storeName") String storeName,
            @PathVariable(name = "coverageName") String coverageName,
            @PathVariable(name = "granuleId") String granuleId) throws IOException {
        GranuleStore store = getGranuleStore(workspaceName, storeName, coverageName);
        Filter filter = getGranuleIdFilter(granuleId);

        store.removeGranules(filter);
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return CoverageStoreInfo.class.isAssignableFrom(methodParameter.getParameterType());
    }

    @Override
    public void configurePersister(XStreamPersister persister, XStreamMessageConverter converter) {
        XStream xstream = persister.getXStream();
        xstream.alias("Schema", IndexSchema.class);
        xstream.alias("Attribute", AttributeTypeInfo.class);
        xstream.omitField(AttributeTypeInfoImpl.class, "featureType");
        xstream.omitField(AttributeTypeInfoImpl.class, "metadata");
        ReflectionConverter rc = new ReflectionConverter(xstream.getMapper(),
                xstream.getReflectionProvider()) {
            @Override
            public boolean canConvert(Class type) {
                return type.equals(IndexSchema.class);
            }

            @Override
            public void marshal(Object original, HierarchicalStreamWriter writer,
                    MarshallingContext context) {
                super.marshal(original, writer, context);
                converter.encodeLink("granules", writer);
            }
        };
        xstream.registerConverter(rc);
    }

    private GranuleSource getGranuleSource(String workspaceName, String storeName,
            String coverageName) throws IOException {
        CoverageInfo coverage = getExistingStructuredCoverage(workspaceName, storeName,
                coverageName);

        StructuredGridCoverage2DReader reader = (StructuredGridCoverage2DReader) coverage
                .getGridCoverageReader(null, null);
        String nativeCoverageName = getNativeCoverageName(coverage, reader);

        GranuleSource source = reader.getGranules(nativeCoverageName, true);
        return source;
    }

    private GranuleStore getGranuleStore(String workspaceName, String storeName,
            String coverageName) throws IOException {
        CoverageInfo coverage = getExistingStructuredCoverage(workspaceName, storeName,
                coverageName);

        StructuredGridCoverage2DReader reader = (StructuredGridCoverage2DReader) coverage
                .getGridCoverageReader(null, null);
        if (reader.isReadOnly()) {
            throw new RestException("Coverage " + coverage.prefixedName() + " is read ony",
                    HttpStatus.METHOD_NOT_ALLOWED);
        }
        String nativeCoverageName = getNativeCoverageName(coverage, reader);

        GranuleStore store = (GranuleStore) reader.getGranules(nativeCoverageName, false);
        return store;
    }

    private String getNativeCoverageName(CoverageInfo coverage,
            StructuredGridCoverage2DReader reader) throws IOException {
        String nativeCoverageName = coverage.getNativeCoverageName();
        if (nativeCoverageName == null) {
            if (reader.getGridCoverageNames().length > 1) {
                throw new IllegalStateException("The grid coverage configuration for "
                        + coverage.getName()
                        + " does not specify a native coverage name, yet the reader provides more than one coverage. "
                        + "Please assign a native coverage name (the GUI does so automatically)");
            } else {
                nativeCoverageName = reader.getGridCoverageNames()[0];
            }
        }
        return nativeCoverageName;
    }

    private SimpleFeatureCollection forceNonNullNamespace(SimpleFeatureCollection features)
            throws IOException {
        SimpleFeatureType sourceSchema = features.getSchema();
        if (sourceSchema.getName().getNamespaceURI() == null) {
            try {
                String targetNs = "http://www.geoserver.org/rest/granules";
                AttributeDescriptor[] attributes = (AttributeDescriptor[]) sourceSchema
                        .getAttributeDescriptors().toArray(new AttributeDescriptor[sourceSchema
                                .getAttributeDescriptors().size()]);
                SimpleFeatureType targetSchema = FeatureTypes.newFeatureType(attributes,
                        sourceSchema.getName().getLocalPart(), new URI(targetNs));
                RetypingFeatureCollection retyped = new RetypingFeatureCollection(features,
                        targetSchema);
                return retyped;
            } catch (Exception e) {
                throw new IOException(
                        "Failed to retype the granules feature schema, in order to force "
                                + "it having a non null namespace",
                        e);
            }
        } else {
            return features;
        }
    }

    private CoverageInfo getExistingStructuredCoverage(String workspaceName, String storeName,
            String coverageName) {
        WorkspaceInfo ws = catalog.getWorkspaceByName(workspaceName);
        if (ws == null) {
            throw new ResourceNotFoundException("No such workspace : " + workspaceName);
        }
        CoverageStoreInfo store = catalog.getCoverageStoreByName(ws, storeName);
        if (store == null) {
            throw new ResourceNotFoundException("No such coverage store: " + storeName);
        }
        Optional<CoverageInfo> optCoverage = catalog.getCoveragesByStore(store).stream()
                .filter(si -> storeName.equals(si.getName())).findFirst();
        if (!optCoverage.isPresent()) {
            throw new ResourceNotFoundException("No such coverage in store: " + coverageName);
        }
        CoverageInfo coverage = optCoverage.get();
        return coverage;
    }

    private Query toQuery(String filter, Integer offset, Integer limit) {
        // build the query
        Query q = new Query(Query.ALL);

        // ... filter
        if (filter != null) {
            try {
                Filter ogcFilter = ECQL.toFilter(filter);
                q.setFilter(ogcFilter);
            } catch (CQLException e) {
                throw new RestException("Invalid cql syntax: " + e.getMessage(),
                        HttpStatus.BAD_REQUEST);
            }
        }

        // ... offset
        if (offset != null) {
            if (offset < 0) {
                throw new RestException("Invalid offset value: " + offset, HttpStatus.BAD_REQUEST);
            }
            q.setStartIndex(offset);
        }

        if (limit != null) {
            if (limit <= 0) {
                throw new RestException("Invalid limit value: " + offset, HttpStatus.BAD_REQUEST);
            }
            q.setMaxFeatures(limit);
        }

        return q;
    }

}