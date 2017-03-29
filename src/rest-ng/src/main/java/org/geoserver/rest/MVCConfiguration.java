package org.geoserver.rest;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.SLDHandler;
import org.geoserver.catalog.StyleHandler;
import org.geoserver.catalog.Styles;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.rest.converters.*;
import org.geotools.util.Version;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.xml.sax.EntityResolver;

import java.util.Comparator;
import java.util.List;

/**
 * Configure various aspects of Spring MVC, in particular message converters
 */
@Configuration
public class MVCConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    protected void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        Catalog catalog = (Catalog) applicationContext.getBean("catalog");


        List<BaseMessageConverter> gsConverters = GeoServerExtensions.extensions(BaseMessageConverter.class);

        //Add default converters
        gsConverters.add(new FreemarkerHTMLMessageConverter("UTF-8"));
        gsConverters.add(new XStreamXMLMessageConverter());
        gsConverters.add(new XStreamJSONMessageConverter());
        gsConverters.add(new XStreamCatalogListConverter.XMLXStreamListConverter());
        gsConverters.add(new XStreamCatalogListConverter.JSONXStreamListConverter());

        //Deal with the various Style handler
        EntityResolver entityResolver = catalog.getResourcePool().getEntityResolver();
        for (StyleHandler sh : Styles.handlers()) {
            for (Version ver : sh.getVersions()) {
                gsConverters.add(new StyleConverter(sh.mimeType(ver), ver, sh, entityResolver));
            }
        }

        //Sort the converters based on ExtensionPriority
        gsConverters.sort(Comparator.comparingInt(BaseMessageConverter::getPriority));
        for (BaseMessageConverter converter : gsConverters) {
            converters.add(converter);
        }

        //use the default ones as lowest priority
        super.addDefaultHttpMessageConverters(converters);
    }

    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RestInterceptor());
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.mediaType("sld", MediaType.valueOf(SLDHandler.MIMETYPE_11));
        configurer.mediaType("json", MediaType.APPLICATION_JSON);
//        configurer.favorPathExtension(true);
        //todo properties files are only supported for test cases. should try to find a way to
        //support them without polluting prod code with handling
//        configurer.mediaType("properties", MediaType.valueOf("application/prs.gs.psl"));
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        //Force MVC to use /restng endpoint. If we need something more advanced, we should make a custom PathHelper
        configurer.setUrlPathHelper(mvcUrlPathHelper());
        configurer.getUrlPathHelper().setAlwaysUseFullPath(true);
    }
}