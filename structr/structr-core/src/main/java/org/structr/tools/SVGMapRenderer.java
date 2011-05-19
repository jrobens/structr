/*
 *  Copyright (C) 2011 Axel Morgner, structr <structr@structr.org>
 * 
 *  This file is part of structr <http://structr.org>.
 * 
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.structr.tools;

import com.flagstone.transform.datatype.Color;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.geotools.data.DataUtilities;
import org.geotools.data.Query;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.FeatureCollections;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.DefaultMapContext;
import org.geotools.map.MapContext;
import org.geotools.map.MapLayer;
import org.geotools.styling.FeatureTypeStyle;
import org.geotools.styling.Rule;
import org.geotools.styling.Style;
import org.geotools.styling.StyleBuilder;
import org.geotools.styling.Symbolizer;
import org.neo4j.gis.spatial.geotools.data.Neo4jSpatialDataStore;
import org.neo4j.graphdb.GraphDatabaseService;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;

import org.structr.common.MapHelper;
import org.structr.common.StandaloneTestHelper;
import org.structr.core.Command;
import org.structr.core.Services;
import org.structr.core.node.GraphDatabaseCommand;

/**
 *
 * @author axel
 */
public class SVGMapRenderer {

    private static final Logger logger = Logger.getLogger(SVGMapRenderer.class.getName());

    public static void main(String[] args) {

        StandaloneTestHelper.prepareStandaloneTest("/opt/structr/t5s/db");

        StringBuilder out = new StringBuilder();

//        final AbstractNode adminNode = (AbstractNode) Services.command(FindUserCommand.class).execute("admin");

        Command graphDbCommand = Services.command(GraphDatabaseCommand.class);
        GraphDatabaseService graphDb = (GraphDatabaseService) graphDbCommand.execute();

        MapContext mapContext = null;
        try {

            long t0 = System.currentTimeMillis();

            String featureName = null;

            String staticFeatureName = "Germany"; //getStaticFeatureName();

            if (StringUtils.isNotBlank(staticFeatureName)) {
                featureName = staticFeatureName;
            } else {
//                HttpServletRequest request = getRequest();
//
//                String featureNameParamName = getFeatureNameParamName();
//                if (featureNameParamName == null) {
//                    featureNameParamName = defaultFeatureParamName;
//                }
//
//                // get the feature name from the request
//                if (request != null) {
//                    featureName = request.getParameter(featureNameParamName);
//                }
            }

            int cx = 896; //getCanvasX();
            int cy = 450; //getCanvasY();

            boolean auto = false; //getAutoEnvelope();

            List<MapLayer> layers = new LinkedList<MapLayer>();
            MapLayer layer = null;
            ReferencedEnvelope envelope = null;

            // For a test, import shapefile

//            String shapeFilePath = "/tmp/structr-maptest/map1.shp";
//            if (shapeFilePath != null) {
//
//                // open data store from shapefile
//                File shapeFile = new File(shapeFilePath);
//                ShapefileDataStore dataStore = new ShapefileDataStore(shapeFile.toURI().toURL());
//
//                // build map layer with style
//                StyleBuilder sb = new StyleBuilder();
//                Symbolizer sym = sb.createLineSymbolizer(Color.decode(getLineColor()), getLineWidth());
//                layer = new MapLayer(dataStore.getFeatureSource(), sb.createStyle(sym));
//                layers.add(layer);
//
//            }

            // open data store from neo4j database
            Neo4jSpatialDataStore n4jstore = new Neo4jSpatialDataStore(graphDb);

            String layerName = "t5s_geodata"; //getLayer();
            if (StringUtils.isEmpty(layerName)) {
                logger.log(Level.SEVERE, "No layer name!");
            }

            SimpleFeatureSource featureSource = n4jstore.getFeatureSource(layerName);
            SimpleFeatureCollection features = null;

            long t1 = System.currentTimeMillis();
            logger.log(Level.INFO, "Data store and feature source ready after {0} ms", (t1 - t0));


            if (auto) {


                if (featureName == null) {

                    // if no feature name is given, show all features of layer
                    envelope = featureSource.getBounds();

                } else {

                    // first, find the feature which corresponds with the requested feature
                    // (or the name of the node, if the request value is empty)
                    List<Filter> filterList = new LinkedList<Filter>();
                    filterList.add(CQL.toFilter("NAME = '" + StringEscapeUtils.escapeSql(featureName) + "'"));
                    Filter filter = MapHelper.featureFactory.or(filterList);
                    Query query = new Query(layerName, filter);

                    features = featureSource.getFeatures(query);
//                    SimpleFeatureCollection featureCollection = featureSource.getFeatures();

                    if (features != null && !(features.isEmpty())) {
                        SimpleFeature requestedFeature = features.features().next();
                        envelope = (ReferencedEnvelope) requestedFeature.getBounds();
                    }

                }


            } else {

                Double eminx = -180.0; //getEnvelopeMinX();
                Double emaxx = 180.0; //getEnvelopeMaxX();
                Double eminy = -90.0; //getEnvelopeMinY();
                Double emaxy = 90.0; //getEnvelopeMaxY();

                if (eminx != null && emaxx != null && eminy != null && emaxy != null) {

                    envelope = new ReferencedEnvelope(eminx, emaxx, eminy, emaxy, null);

                } else {
                    logger.log(Level.WARNING, "Manual envelope parameter incomplete");
                }
            }

            // Expand envelope as needed
            MapHelper.expandEnvelope(envelope, new Double(cx), new Double(cy));

            // search all features within this bounding
//            SimpleFeatureCollection features = MapHelper.getIntersectingFeatures(graphDb, envelope, layerName);
            if (features == null) {
                features = MapHelper.getIntersectingFeatures(graphDb, envelope, layerName);
            }

            logger.log(Level.INFO, "{0} intersecting features found", features.size());

            // create a style for displaying the polygons
//            Symbolizer polygonSymbolizer = MapHelper.createPolygonSymbolizer("#ffffff", 1, 1, "#d9d4ce", 1);
            Symbolizer polygonSymbolizer = MapHelper.createPolygonSymbolizer("#000000", 1, 1, "#d9d4ce", 1);
            Symbolizer textSymbolizer = MapHelper.createTextSymbolizer("Arial", 16, "#ffffff", 1, 0.5, 0.0, 0.0, 0.0);


            Rule rule = MapHelper.styleFactory.createRule();
            rule.symbolizers().add(polygonSymbolizer);
            rule.symbolizers().add(textSymbolizer);

            FeatureTypeStyle fts = MapHelper.styleFactory.createFeatureTypeStyle(new Rule[]{rule});
            Style style = MapHelper.styleFactory.createStyle();

            style.featureTypeStyles().add(fts);

            final SimpleFeatureType TYPE = DataUtilities.createType("Location",
                    "geom:Point,NAME:String,name:String" //"location:Point," + // <- the geometry attribute: Point type
                    //"NAME:String," + // <- a String attribute
                    //"number:Integer" // a number attribute
                    );

            SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(TYPE);
            SimpleFeatureCollection collection = FeatureCollections.newCollection();

            //WKTReader2 wkt = new WKTReader2();
            //collection.add( SimpleFeatureBuilder.build( TYPE, new Object[]{ wkt.read("POINT(8.4 50.6)"), "Frankfurt2"}, null) );

            GeometryFactory geometryFactory = JTSFactoryFinder.getGeometryFactory(null);
            Coordinate coord = new Coordinate(8.4, 50.6);
            Point point = geometryFactory.createPoint(coord);

            featureBuilder.add(point);
            featureBuilder.add("Frankfurt");
//            featureBuilder.add("Frankfurt");
//            featureBuilder.add(123);
            SimpleFeature feature = featureBuilder.buildFeature(null);
            collection.add(feature);

            Symbolizer cityTextSym = MapHelper.createTextSymbolizer("Arial", 13, "#000000", 1.0, 0.5, 0.0, 0.5, 0.5);
            Symbolizer cityPointSym = MapHelper.createPointSymbolizer("Circle", 5, "#000000", 1, "#000000", 1.0);
//            Symbolizer cityPolygonSymbolizer = MapHelper.createPolygonSymbolizer("#000000", 1, 1, "#000000", 1);

            Rule rule2 = MapHelper.styleFactory.createRule();
            rule2.symbolizers().add(cityTextSym);
//            rule2.symbolizers().add(cityPolygonSymbolizer);
            rule2.symbolizers().add(cityPointSym);

            FeatureTypeStyle fts2 = MapHelper.styleFactory.createFeatureTypeStyle(new Rule[]{rule2});
            Style style2 = MapHelper.styleFactory.createStyle();
            style2.featureTypeStyles().add(fts2);

            SimpleFeatureSource source = DataUtilities.source(collection);
            SimpleFeatureCollection cityFeatures = source.getFeatures();

            //Style pointStyle = SLD.createPointStyle("Square", Color.yellow, Color.yellow, 1, 3);
            //pointStyle.featureTypeStyles().add(fts2);

            // add features and style as a map layer to the list of map layers
            layers.add(new MapLayer(features, style));
            layers.add(new MapLayer(cityFeatures, style2));

            // create a map context
            mapContext = new DefaultMapContext(layers.toArray(new MapLayer[]{}));

            long t2 = System.currentTimeMillis();

            // render map to SVG
            MapHelper.renderSVGDocument(out, mapContext, envelope, cx, cy, true, true);

            long t3 = System.currentTimeMillis();
            logger.log(Level.INFO, "renderSVGDocument took {0} ms", (t3 - t2));

            // clear map content
            mapContext.dispose();

            long t4 = System.currentTimeMillis();
            logger.log(Level.INFO, "SVG image successfully created in {0} ms", (t4 - t0));

            System.out.println("Size: " + out.length() + " bytes");
            FileUtils.writeStringToFile(new File("/home/axel/structr_test.svg"), out.toString());


        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Error while rendering map to SVG", t);
        } finally {
            if (mapContext != null) {
                mapContext.dispose();
            }
        }
        StandaloneTestHelper.finishStandaloneTest();

    }
}
