/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.huawei.unibi.molap.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import com.huawei.iweb.platform.logging.LogService;
import com.huawei.iweb.platform.logging.LogServiceFactory;
import com.huawei.unibi.molap.api.dataloader.SchemaInfo;
import com.huawei.unibi.molap.constants.MolapCommonConstants;
import com.huawei.unibi.molap.datastorage.store.impl.FileFactory;
import com.huawei.unibi.molap.graphgenerator.GraphGenerator;
import com.huawei.unibi.molap.keygenerator.factory.KeyGeneratorFactory;
import com.huawei.unibi.molap.metadata.MolapMetadata;
import com.huawei.unibi.molap.olap.DimensionType;
import com.huawei.unibi.molap.olap.LevelType;
import com.huawei.unibi.molap.olap.MolapDef;
import com.huawei.unibi.molap.olap.MolapDef.*;
import com.huawei.unibi.molap.olap.Util;
import com.huawei.unibi.molap.schema.metadata.AggregateTable;
import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.XOMException;
import org.eigenbase.xom.XOMUtil;

public final class MolapSchemaParser {
    /**
     *
     */
    public static final String QUOTES = "\"";

    /**
     * BACK_TICK
     */
    public static final String BACK_TICK = "`";

    private static final LogService LOGGER =
            LogServiceFactory.getLogService(GraphGenerator.class.getName());

    private MolapSchemaParser() {

    }

    /**
     * Get a Mondrian Schema,not connects to the DB
     *
     * @param catalogUrl The schema file path
     * @return MolapDef.Schema
     */
    public static Schema loadXML(String catalogUrl) {
        Schema xmlSchema = null;
        try {
            final Parser xmlParser = XOMUtil.createDefaultParser();
            //
            final DOMWrapper def;
            //
            InputStream in = null;
            try {
                in = FileFactory
                        .getDataInputStream(catalogUrl, FileFactory.getFileType(catalogUrl));
                def = xmlParser.parse(in);
            } finally {
                if (in != null) {
                    in.close();
                }
            }
            //
            xmlSchema = new Schema(def);
        } catch (XOMException e) {
            throw Util.newError(e, "while parsing catalog " + catalogUrl);
        }
        //
        catch (IOException e) {
            throw Util.newError(e, "while parsing catalog " + catalogUrl);
        }

        return xmlSchema;
    }

    public static Cube getMondrianCube(Schema schema, String cubeName) {
        Cube[] cubes = schema.cubes;
        for (Cube cube : cubes) {
            if (cube.name.equalsIgnoreCase(cubeName)) {
                return cube;
            }
        }
        return null;
    }

    /**
     * This method Return the dimension queries based on quotest required or not.
     *
     * @param dimensions
     * @return
     */
    public static String getDimensionSQLQueries(Cube cube, CubeDimension[] dimensions,
            Schema schema, boolean isQuotesRequired, String quote) {
        if (isQuotesRequired) {
            return getDimensionSQLQueriesWithQuotes(cube, dimensions, schema, quote);
        } else {
            return getDimensionSQLQueries(cube, dimensions, schema);
        }
    }

    public static String getDenormColNames(CubeDimension[] dimensions, Schema schema) {
        //
        List<String> foreignKeys = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);

        for (CubeDimension dim : dimensions) {
            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, dim);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                if (relation == null) {
                    continue;
                }

                foreignKeys.add(dim.foreignKey);

            }
        }

        StringBuilder columns = new StringBuilder();

        for (CubeDimension dim : dimensions) {
            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, dim);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                if (relation == null) {

                    for (Level levels : hierarchy.levels) {
                        if (levels.parentname != null) continue;
                        if (foreignKeys.contains(levels.name)) {
                            columns.append(levels.name);
                            columns.append(MolapCommonConstants.HASH_SPC_CHARACTER);
                        }

                    }

                }

            }
        }

        String columnstr = columns.toString();
        if (columnstr.length() > 0 && columnstr.endsWith(MolapCommonConstants.HASH_SPC_CHARACTER)) {
            columnstr = columnstr.substring(0,
                    columnstr.length() - MolapCommonConstants.HASH_SPC_CHARACTER.length());
        }

        return columnstr;
    }

    private static String getDimensionSQLQueries(Cube cube, CubeDimension[] dimensions,
            Schema schema) {
        //
        List<String> queryList = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        for (CubeDimension dim : dimensions) {
            if (!dim.visible) {
                continue;
            }

            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, dim);
            StringBuilder query;
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                if (relation == null) {
                    continue;
                }
                String tableName = ((Table) hierarchy.relation).name;

                //check if fact table name is same as dimension table then skip the dimension loading.
                String factTableName = getFactTableName(cube);
                if (factTableName.equals(tableName)) {
                    continue;
                }

                String dimName = dim.name;
                dimName = dimName.replaceAll(" ", "_");

                if (null == hierarchy.name) {
                    query = new StringBuilder(
                            dimName + '_' + dimName + MolapCommonConstants.COLON_SPC_CHARACTER);
                } else {
                    String hierName = hierarchy.name.replaceAll(" ", "_");
                    query = new StringBuilder(
                            dimName + '_' + hierName + MolapCommonConstants.COLON_SPC_CHARACTER);
                }
                //
                query.append("SELECT ");

                query.append(hierarchy.primaryKey + ',');

                query.append(hierarchy.levels[0].column);
                //
                if (hasOrdinalColumn(hierarchy.levels[0])) {
                    query.append(',' + hierarchy.levels[0].ordinalColumn);
                }

                addNameColumnAndPropertyInQuery(tableName, hierarchy.levels[0].column, dimensions,
                        schema, query);

                for (int i = 1; i < hierarchy.levels.length; i++) {
                    query.append(',' + hierarchy.levels[i].column);

                    if (hasOrdinalColumn(hierarchy.levels[0])) {
                        query.append(',' + hierarchy.levels[0].ordinalColumn);
                    }

                    addNameColumnAndPropertyInQuery(tableName, hierarchy.levels[0].column,
                            dimensions, schema, query);

                }
                query.append(" FROM " + tableName);
                queryList.add(query.toString());
            }
        }
        StringBuilder finalQuryString = new StringBuilder();

        for (int i = 0; i < queryList.size() - 1; i++) {
            finalQuryString.append(queryList.get(i));
            finalQuryString.append(MolapCommonConstants.HASH_SPC_CHARACTER);
        }
        if (queryList.size() > 0) {
            finalQuryString.append(queryList.get(queryList.size() - 1));
        }
        return finalQuryString.toString();
    }

    private static String getDimensionSQLQueriesWithQuotes(Cube cube, CubeDimension[] dimensions,
            Schema schema, String quotes) {
        List<String> queryList = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        for (CubeDimension dim : dimensions) {
            if (!dim.visible) {
                continue;
            }

            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, dim);
            for (Hierarchy hrrchy : hierarchies) {
                StringBuilder query;
                RelationOrJoin relation = hrrchy.relation;
                if (relation == null) {
                    continue;
                }
                String tableName = ((Table) hrrchy.relation).name;
                //check if fact table name is same as dimension table then skip the dimension loading.
                String factTableName = getFactTableName(cube);
                if (factTableName.equals(tableName)) {
                    continue;
                }
                String dimName = dim.name;
                dimName = dimName.replaceAll(" ", "_");

                if (null == hrrchy.name) {
                    query = new StringBuilder(
                            dimName + '_' + dimName + MolapCommonConstants.COLON_SPC_CHARACTER);
                } else {
                    String hierName = hrrchy.name.replaceAll(" ", "_");
                    query = new StringBuilder(
                            dimName + '_' + hierName + MolapCommonConstants.COLON_SPC_CHARACTER);
                }
                //
                query.append("SELECT ");

                query.append(quotes + hrrchy.primaryKey + quotes + ',');

                query.append(quotes + hrrchy.levels[0].column + quotes);
                //
                if (hasOrdinalColumn(hrrchy.levels[0])) {
                    query.append(',' + quotes + hrrchy.levels[0].ordinalColumn + quotes);
                }

                addNameColumnAndPropertyInQueryWithQuotes(tableName, hrrchy.levels[0].column,
                        dimensions, schema, query, quotes);

                for (int i = 1; i < hrrchy.levels.length; i++) {
                    query.append(',' + quotes + hrrchy.levels[i].column + quotes);
                    if (hasOrdinalColumn(hrrchy.levels[0])) {
                        query.append(',' + quotes + hrrchy.levels[0].ordinalColumn + quotes);
                    }

                    addNameColumnAndPropertyInQueryWithQuotes(tableName, hrrchy.levels[i].column,
                            dimensions, schema, query, quotes);

                }
                query.append(" FROM " + quotes + tableName + quotes);
                queryList.add(query.toString());
            }
        }
        StringBuilder finalQuryString = new StringBuilder();

        for (int i = 0; i < queryList.size() - 1; i++) {
            finalQuryString.append(queryList.get(i));
            finalQuryString.append(MolapCommonConstants.HASH_SPC_CHARACTER);
        }
        if (queryList.size() > 0) {
            finalQuryString.append(queryList.get(queryList.size() - 1));
        }
        return finalQuryString.toString();
    }

    private static void addNameColumnAndPropertyInQueryWithQuotes(String tableName,
            String levelColumn, CubeDimension[] dimensions, Schema schema, StringBuilder query,
            String quotes) {

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                if (relation == null) {
                    continue;
                }
                String dimTableName = ((Table) hierarchy.relation).name;
                if (tableName.equals(dimTableName)) {
                    for (Level level : hierarchy.levels) {
                        if (level.parentname != null) continue;
                        if (levelColumn.equals(level.column)) {
                            if (null != level.nameColumn) {
                                query.append(',' + quotes + level.nameColumn + quotes);
                            }
                            //
                            Property[] properties = level.properties;
                            if (properties.length > 0) {
                                for (int j = 0; j < properties.length; j++) {
                                    query.append(',' + quotes + properties[j].column + quotes);
                                }
                            }
                        }
                    }
                }

            }
        }

    }

    private static void addNameColumnAndPropertyInQuery(String tableName, String levelColumn,
            CubeDimension[] dimensions, Schema schema, StringBuilder query) {

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                if (relation == null) {
                    continue;
                }
                String dimensionTableName = ((Table) hierarchy.relation).name;
                if (tableName.equals(dimensionTableName)) {
                    for (Level level : hierarchy.levels) {
                        if (level.parentname != null) continue;
                        if (levelColumn.equals(level.column)) {
                            if (null != level.nameColumn) {
                                query.append(',' + level.nameColumn);
                            }
                            //
                            Property[] properties = level.properties;
                            if (properties.length > 0) {
                                for (int j = 0; j < properties.length; j++) {
                                    query.append(',' + properties[j].column);
                                }
                            }
                        }
                    }
                }

            }
        }

    }

    public static String getTableInputSQLQuery(CubeDimension[] dimensions, Measure[] measures,
            String factTableName, boolean isQuotesRequired, Schema schema) {
        StringBuilder query = new StringBuilder("SELECT ");

        getQueryForDimension(dimensions, query, factTableName, isQuotesRequired, schema);

        if (checkIfDenormalized(dimensions, schema)) {
            if (!isQuotesRequired) {
                getPropetiesQuerypart(dimensions, query, factTableName, schema);
            } else {
                getPropetiesQuerypartWithQuotes(dimensions, query, factTableName, schema);
            }
        }
        if (!"select".equalsIgnoreCase(query.toString().trim())) {
            query.append(",");
        }
        Set<String> uniqueMsrCols =
                new HashSet<String>(MolapCommonConstants.DEFAULT_COLLECTION_SIZE);
        for (int i = 0; i < measures.length; i++) {

            uniqueMsrCols.add(measures[i].column);
        }
        String[] uniqueMeasure = uniqueMsrCols.toArray(new String[uniqueMsrCols.size()]);
        for (int j = 0; j < uniqueMeasure.length; j++) {
            query.append(System.getProperty("line.separator"));
            if (isQuotesRequired) {
                query.append(QUOTES + uniqueMeasure[j] + QUOTES);
            } else {
                query.append(uniqueMeasure[j]);
            }

            if (j != uniqueMeasure.length - 1) {

                query.append(",");
            }
        }
        query.append(System.getProperty("line.separator"));

        if (isQuotesRequired) {
            query.append(" FROM " + QUOTES + factTableName + QUOTES + ' ');
        } else {
            query.append(" FROM " + factTableName + ' ');
        }

        return query.toString();
    }

    private static boolean checkIfDenormalized(CubeDimension[] dimensions, Schema schema) {
        for (int i = 0; i < dimensions.length; i++) {
            Hierarchy[] hierarchies = extractHierarchies(schema, dimensions[i]);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                if (null != relation && null != ((Table) hierarchy.relation).name) {
                    return false;
                }
            }
        }
        return true;
    }

    public static String getTableInputSQLQueryForAGG(String[] aggDim, String[] measures,
            String factTableName, boolean isQuotesRequired) {
        StringBuilder queryBuilder = new StringBuilder("SELECT ");
        queryBuilder.append(System.getProperty("line.separator"));
        for (int i = 0; i < aggDim.length; i++) {
            if (isQuotesRequired) {
                queryBuilder.append(QUOTES + aggDim[i] + QUOTES);
            } else {
                queryBuilder.append(aggDim[i]);
            }
            queryBuilder.append(",");
            queryBuilder.append(System.getProperty("line.separator"));
        }

        for (int i = 0; i < measures.length - 1; i++) {
            if (isQuotesRequired) {
                queryBuilder.append(QUOTES + measures[i] + QUOTES);
            } else {
                queryBuilder.append(measures[i]);
            }
            queryBuilder.append(",");
            queryBuilder.append(System.getProperty("line.separator"));
        }
        if (isQuotesRequired) {
            queryBuilder.append(QUOTES + measures[measures.length - 1] + QUOTES);
            queryBuilder.append(System.getProperty("line.separator"));
            queryBuilder.append(" FROM " + QUOTES + factTableName + QUOTES);
        } else {
            queryBuilder.append(measures[measures.length - 1]);
            queryBuilder.append(System.getProperty("line.separator"));
            queryBuilder.append(" FROM " + factTableName);
        }
        return queryBuilder.toString();
    }

    private static void getPropetiesQuerypart(CubeDimension[] dimensions, StringBuilder query,
            String factTableName, Schema schema) {
        Property[] properties;
        for (CubeDimension cDim : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDim);
            for (Hierarchy hierarchy : hierarchies) {
                if (hierarchy.normalized) {
                    continue;
                }
                RelationOrJoin relation = hierarchy.relation;
                String dimName = cDim.name;
                dimName = dimName.replaceAll(" ", "_");

                String hierarchyTbl =
                        relation == null ? factTableName : ((Table) hierarchy.relation).name;
                if (hasOrdinalColumn(hierarchy.levels[0])) {
                    query.append(System.getProperty("line.separator"));
                    query.append(',' + hierarchyTbl + '.' + hierarchy.levels[0].ordinalColumn);
                }
                if (null != hierarchy.levels[0].nameColumn) {
                    query.append(System.getProperty("line.separator"));
                    query.append(',' + hierarchyTbl + '.' + hierarchy.levels[0].nameColumn);
                }
                properties = hierarchy.levels[0].properties;
                if (properties.length > 0) {
                    for (int j = 0; j < properties.length; j++) {
                        query.append(System.getProperty("line.separator"));
                        query.append(',' + hierarchyTbl + '.' + properties[j].column);
                    }
                }
                for (int i = 1; i < hierarchy.levels.length; i++) {
                    if (hasOrdinalColumn(hierarchy.levels[i])) {
                        query.append(System.getProperty("line.separator"));
                        query.append(',' + hierarchyTbl + '.' + hierarchy.levels[i].ordinalColumn);
                    }
                    if (null != hierarchy.levels[i].nameColumn) {
                        query.append(System.getProperty("line.separator"));
                        query.append(',' + hierarchyTbl + '.' + hierarchy.levels[i].nameColumn);
                    }
                    properties = hierarchy.levels[i].properties;
                    if (properties.length > 0) {
                        for (int j = 0; j < properties.length; j++) {
                            query.append(System.getProperty("line.separator"));
                            query.append(',' + hierarchyTbl + '.' + properties[j].column);
                        }
                    }
                }
            }
        }
    }

    private static void getPropetiesQuerypartWithQuotes(CubeDimension[] dimensions,
            StringBuilder query, String factTableName, Schema schema) {
        Property[] properties;
        for (CubeDimension cDim : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDim);
            for (Hierarchy hierarchy : hierarchies) {
                if (hierarchy.normalized) {
                    continue;
                }
                RelationOrJoin relation = hierarchy.relation;

                String hierarchyTable =
                        relation == null ? factTableName : ((Table) hierarchy.relation).name;
                if (hasOrdinalColumn(hierarchy.levels[0])) {
                    query.append(System.getProperty("line.separator"));
                    query.append(',' + QUOTES + hierarchyTable + QUOTES + '.' + QUOTES
                            + hierarchy.levels[0].ordinalColumn + QUOTES);
                }
                if (null != hierarchy.levels[0].nameColumn) {
                    query.append(System.getProperty("line.separator"));
                    query.append(',' + QUOTES + hierarchyTable + QUOTES + '.' + QUOTES
                            + hierarchy.levels[0].nameColumn + QUOTES);
                }
                properties = hierarchy.levels[0].properties;
                if (properties.length > 0) {
                    for (int j = 0; j < properties.length; j++) {
                        query.append(System.getProperty("line.separator"));
                        query.append(',' + QUOTES + hierarchyTable + QUOTES + '.' + QUOTES
                                + properties[j].column + QUOTES);
                    }
                }
                for (int i = 1; i < hierarchy.levels.length; i++) {
                    if (hasOrdinalColumn(hierarchy.levels[i])) {
                        query.append(System.getProperty("line.separator"));
                        query.append(',' + QUOTES + hierarchyTable + QUOTES + '.' + QUOTES
                                + hierarchy.levels[i].ordinalColumn + QUOTES);
                    }
                    if (null != hierarchy.levels[i].nameColumn) {
                        query.append(System.getProperty("line.separator"));
                        query.append(',' + QUOTES + hierarchyTable + QUOTES + '.' + QUOTES
                                + hierarchy.levels[i].nameColumn + QUOTES);
                    }
                    properties = hierarchy.levels[i].properties;
                    if (properties.length > 0) {
                        for (int j = 0; j < properties.length; j++) {
                            query.append(System.getProperty("line.separator"));
                            query.append(',' + QUOTES + hierarchyTable + QUOTES + '.' + QUOTES
                                    + properties[j].column + QUOTES);
                        }
                    }
                }
            }
        }
    }

    private static int getQueryForDimension(CubeDimension[] dimensions, StringBuilder query,
            String factTableName, boolean isQuotesRequired, Schema schema) {
        int counter = 0;
        for (CubeDimension cDim : dimensions) {
            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDim);
            for (Hierarchy hierarchy : hierarchies) { //Added for Normalized Hiercarhy AR-UniBI-OLAP-003

                if (hierarchy.normalized) {
                    query.append(System.getProperty("line.separator"));
                    if (counter != 0) {
                        query.append(',');
                    }

                    if (isQuotesRequired) {
                        query.append(QUOTES + cDim.foreignKey + QUOTES);
                    } else {
                        query.append(cDim.foreignKey);
                    }
                    counter++;
                    continue;
                }

                if (hierarchy.primaryKey != null && cDim.foreignKey != null) {
                    query.append(System.getProperty("line.separator"));
                    if (counter != 0) {
                        query.append(',');
                    }

                    if (isQuotesRequired) {
                        query.append(QUOTES + cDim.foreignKey + QUOTES);
                    } else {
                        query.append(cDim.foreignKey);
                    }
                    counter++;
                    continue;
                } else {

                    Level[] levels = hierarchy.levels;
                    for (Level level : levels) {
                        if (level.parentname != null) continue;
                        query.append(System.getProperty("line.separator"));
                        if (counter != 0) {
                            query.append(',');
                        }

                        if (isQuotesRequired) {
                            query.append(
                                    QUOTES + factTableName + QUOTES + '.' + QUOTES + level.column
                                            + QUOTES);
                        } else {
                            query.append(factTableName + '.' + level.column);
                        }
                        counter++;
                    }
                }
            }
        }
        return counter;
    }

    /**
     * Get dimension string from a array of CubeDimension,which can be shared
     * CubeDimension within schema or in a cube.
     *
     * @param cube
     * @param dimensions
     * @return
     */
    public static int getDimensionString(Cube cube, CubeDimension[] dimensions,
            StringBuilder dimString, int counter, Schema schema) {
        for (CubeDimension cDimension : dimensions) {
            if (cDimension.highCardinality) {
                continue;
            }
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;

                String tableName = relation == null ?
                        getFactTableName(cube) :
                        ((Table) hierarchy.relation).name;
                int i = hierarchy.levels.length;
                boolean appendComma = true;
                for (Level level : hierarchy.levels) { //Added for Normalized hierarchy AR-UniBI-OLAP-003
                    if (level.parentname != null) {
                        appendComma = false;
                        continue;
                    }
                    if (hierarchy.normalized) {
                        if (i == 1) {
                            dimString.append(tableName + '_' + level.column
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + counter
                                    + MolapCommonConstants.COLON_SPC_CHARACTER
                                    + level.levelCardinality
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + 'Y');

                        } else {
                            dimString.append(tableName + '_' + level.column
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + counter
                                    + MolapCommonConstants.COLON_SPC_CHARACTER
                                    + level.levelCardinality
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + 'N');
                        }
                        if (i > 1) {
                            dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);

                        }
                        counter++;
                    } else {

                        dimString.append(tableName + '_' + level.column
                                + MolapCommonConstants.COLON_SPC_CHARACTER + counter
                                + MolapCommonConstants.COLON_SPC_CHARACTER + level.levelCardinality
                                + MolapCommonConstants.COLON_SPC_CHARACTER + 'Y');
                        counter++;
                        if (i > 1) {
                            dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);

                        }

                    }

                    i--;

                }
                if (appendComma) dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
            }
        }
        return counter;
    }

    public static int getDimensionStringForAgg(String[] dimensions, StringBuilder dimString,
            int counter, Map<String, String> dimCardinalities, String[] acutalDimension) {
        int len = dimensions.length;
        for (int i = 0; i < len - 1; i++) {
            dimString.append(dimensions[i]);
            dimString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
            dimString.append(counter++);
            dimString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
            dimString.append(dimCardinalities.get(acutalDimension[i]));
            dimString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
            dimString.append("Y");
            dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
        }
        //
        dimString.append(dimensions[len - 1]);
        dimString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
        dimString.append(counter++);
        dimString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
        dimString.append(dimCardinalities.get(acutalDimension[len - 1]));
        dimString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
        dimString.append("Y");
        return counter;
    }

    /**
     * Return mapping of Column name to cardinality
     */
    public static Map<String, String> getCardinalities(String factTableName,
            CubeDimension[] dimensions, Schema schema) {
        Map<String, String> cardinalities = new LinkedHashMap<String, String>();
        //
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            //
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;

                String tableName =
                        relation == null ? factTableName : ((Table) hierarchy.relation).name;
                int counter = 0;
                for (Level level : hierarchy.levels) {
                    if (level.parentname != null) continue;
                    if (hierarchy.normalized) {
                        if (counter == hierarchy.levels.length - 1) {
                            cardinalities.put(tableName + '_' + level.column,
                                    level.levelCardinality + "");
                        }
                    } else {
                        cardinalities
                                .put(tableName + '_' + level.column, level.levelCardinality + "");
                    }
                    counter++;

                }
            }
        }
        return cardinalities;
    }

    /**
     * Get measure string from a array of Measure
     *
     * @param measures
     * @return
     */
    public static String getMeasureString(Measure[] measures, int counter) {
        StringBuilder measureString = new StringBuilder();
        int i = measures.length;
        for (Measure measure : measures) {

            measureString
                    .append(measure.column + MolapCommonConstants.COLON_SPC_CHARACTER + counter);
            counter++;
            if (i > 1) {
                measureString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
            }
            i--;

        }
        return measureString.toString();
    }

    /**
     * Get measure string from a array of Measure
     *
     * @param measures
     * @return
     */
    public static String getMeasureStringForAgg(String[] measures, int counter) {
        StringBuilder measureString = new StringBuilder();
        int i = measures.length;
        for (String measure : measures) {

            measureString.append(measure + MolapCommonConstants.COLON_SPC_CHARACTER + counter);
            counter++;
            if (i > 1) {
                measureString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
            }
            i--;

        }
        return measureString.toString();
    }

    /**
     * Get measure string from a array of Measure
     *
     * @param measures
     * @return
     */
    public static String[] getMeasures(Measure[] measures) {
        String[] measuresStringArray = new String[measures.length];

        for (int i = 0; i < measuresStringArray.length; i++) {
            measuresStringArray[i] = measures[i].column;
        }
        return measuresStringArray;
    }

    /**
     * Get hierarchy string from dimensions
     *
     * @param dimensions
     * @return
     */
    public static String getHierarchyString(CubeDimension[] dimensions, Schema schema) {
        StringBuilder hierString = new StringBuilder();
        int hierIndex = -1;
        String hierStr = "";
        int lengthOfLevels = 0;
        int counter = 0;

        for (CubeDimension cDimension : dimensions) {
            if (cDimension.highCardinality) {
                continue;
            }
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            String cDimName = cDimension.name;

            for (Hierarchy hierarchy : hierarchies) {
                String hName = hierarchy.name;
                if (hName == null || "".equals(hName.trim())) {
                    hName = cDimName;
                }
                // Replace the hierarchy name space with "_"
                hName = hName.replaceAll(" ", "_");
                cDimName = cDimName.replaceAll(" ", "_");

                lengthOfLevels = hierarchy.levels.length;
                int hierlength = hierarchy.levels.length;
                if (hierlength > 0) {
                    StringBuilder localString = new StringBuilder();

                    for (int i = 0; i < hierlength; i++) {
                        if (hierIndex == -1) {
                            localString.append(counter++);
                        } else {
                            localString.append(++hierIndex);
                        }

                        if (lengthOfLevels > 1) {
                            localString.append(MolapCommonConstants.COMA_SPC_CHARACTER);

                        }
                        lengthOfLevels--;
                    }
                    localString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
                    hierStr = localString.toString();
                    hierStr = cDimName + '_' + hName + MolapCommonConstants.COLON_SPC_CHARACTER
                            + hierStr;
                    hierString.append(hierStr);
                } else {
                    counter++;
                }

            }
        }

        hierStr = hierString.toString();
        if (hierStr.length() > 0 && hierStr
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            hierStr = hierStr.substring(0,
                    hierStr.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER.length());
        }
        return hierStr;
    }

    /**
     * Get the name of a fact table in a cube
     *
     * @param cube
     * @return
     */
    public static String getFactTableName(Cube cube) {
        MolapDef.Table factTable = (MolapDef.Table) cube.fact;
        return factTable.name;
    }

    public static String[] getCubeDimensions(Cube cube, Schema schema) {
        List<String> list = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        MolapDef.CubeDimension[] dimensions = cube.dimensions;
        for (CubeDimension cDimension : dimensions) {
            //Ignoring the dimensions which are high cardinality dimension
            if (cDimension.highCardinality) {
                continue;
            }
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                String factTableName = getFactTableName(cube);
                list.addAll(getTableNames(factTableName, hierarchy));
            }
        }
        String[] fields = new String[list.size()];
        fields = list.toArray(fields);
        return fields;
    }

    /**
     * Extracts the hierarchy from Dimension or Dimension usage(basedon multiple cubes)
     *
     * @param schema
     * @param cDimension
     * @return
     */
    public static Hierarchy[] extractHierarchies(Schema schema, CubeDimension cDimension) {
        Hierarchy[] hierarchies = null;
        if (cDimension instanceof Dimension) {
            hierarchies = ((Dimension) cDimension).hierarchies;
        } else if (cDimension instanceof DimensionUsage) {
            String sourceDimensionName = ((DimensionUsage) cDimension).source;
            Dimension[] schemaGlobalDimensions = schema.dimensions;
            for (Dimension dimension : schemaGlobalDimensions) {
                if (sourceDimensionName.equals(dimension.name)) {
                    hierarchies = dimension.hierarchies;
                }
            }
        }
        return hierarchies;
    }

    private static List<String> getTableNames(String factTableName, Hierarchy hierarchy) {
        List<String> list = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        RelationOrJoin relation = hierarchy.relation;
        String tableName = relation == null ? factTableName : ((Table) hierarchy.relation).name;
        for (Level level : hierarchy.levels) {
            if (level.parentname != null) continue;
            list.add(tableName + '_' + level.column);

        }
        return list;
    }

    public static String[] getDimensions(Cube cube, Schema schema) {  //
        List<String> list = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        MolapDef.CubeDimension[] dimensions = cube.dimensions;
        for (CubeDimension cDimension : dimensions) {
            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;

                String tableName = relation == null ?
                        getFactTableName(cube) :
                        ((Table) hierarchy.relation).name;
                for (Level level : hierarchy.levels) {
                    if (level.parentname != null) continue;
                    list.add(tableName + '_' + level.column);
                }
            }
        }
        String[] fields = new String[list.size()];
        fields = list.toArray(fields);
        return fields;
    }

    public static AggregateTable[] getAggregateTable(Cube cube, Schema schema) {
        MolapDef.Table table = (MolapDef.Table) cube.fact;
        MolapDef.AggTable[] aggTables = table.aggTables;
        int numberOfAggregates = aggTables.length;
        AggregateTable[] aggregates = new AggregateTable[numberOfAggregates];
        List<List<String[]>> aggregatorList = new ArrayList<List<String[]>>(numberOfAggregates);

        for (int i = 0; i < aggregates.length; i++) {
            aggregates[i] = new AggregateTable();
            String name = ((MolapDef.AggName) aggTables[i]).getNameAttribute();
            aggregatorList.add(getCubeMeasureAggregatorDetails(name, cube));
        }

        for (int i = 0; i < numberOfAggregates; i++) {
            List<String[]> aggregators = aggregatorList.get(i);
            String[] measureColumns = aggregators.get(0);
            String[] aggs = aggregators.get(1);
            String[] measureName = aggregators.get(2);
            String[] aggClass = aggregators.get(3);
            String[] columnName = aggregators.get(4);

            String name = ((MolapDef.AggName) aggTables[i]).getNameAttribute();
            aggregates[i].setAggregateTableName(name);

            MolapDef.AggFactCount factcount = aggTables[i].factcount;
            String factCountName = factcount.column;

            //for AggMeasure,we assume its numbers and order is the same as the
            //Cube's.
            if (aggTables[i].factcount != null) {
                String[] newAggs = new String[aggs.length + 1];
                System.arraycopy(aggs, 0, newAggs, 0, aggs.length);
                newAggs[aggs.length] = "count";

                String[] newCols = new String[aggs.length + 1];
                System.arraycopy(measureColumns, 0, newCols, 0, measureColumns.length);
                newCols[aggs.length] = factCountName;

                String[] newNames = new String[aggs.length + 1];
                System.arraycopy(measureName, 0, newNames, 0, measureName.length);
                newNames[aggs.length] = factCountName;

                String[] newClass = new String[aggs.length + 1];
                System.arraycopy(aggClass, 0, newClass, 0, aggClass.length);
                newClass[aggs.length] = null;

                String[] newColumn = new String[aggs.length + 1];
                System.arraycopy(columnName, 0, newColumn, 0, columnName.length);
                newColumn[aggs.length] = factCountName;

                aggregates[i].setAggMeasure(newCols);
                aggregates[i].setAggregator(newAggs);
                aggregates[i].setAggNames(newNames);
                aggregates[i].setAggregateClass(newClass);
                aggregates[i].setAggColuName(newColumn);
            } else {
                aggregates[i].setAggMeasure(measureColumns);
                aggregates[i].setAggregator(aggs);
                aggregates[i].setAggNames(measureName);
                aggregates[i].setAggregateClass(aggClass);
                aggregates[i].setAggColuName(columnName);
            }

            MolapDef.AggLevel[] levels = aggTables[i].levels;
            String[] newLevel = getLevelsWithTableName(levels, cube, schema, false);
            String[] newLevelWithTableName = getLevelsWithTableName(levels, cube, schema, true);
            String[] lvls = new String[levels.length];
            for (int j = 0; j < levels.length; j++) {
                lvls[j] = levels[j].getColumnName();
            }
            aggregates[i].setAggLevels(lvls);
            aggregates[i].setActualAggLevels(newLevel);
            aggregates[i].setAggLevelsActualName(newLevelWithTableName);
        }
        return aggregates;
    }

    private static List<String[]> getCubeMeasureAggregatorDetails(String aggTableName, Cube cube) {
        MolapDef.Table table = (MolapDef.Table) cube.fact;
        MolapDef.AggTable[] aggTables = table.aggTables;
        int numberOfAggregates = aggTables.length;

        List<String[]> cubeMeasures = new ArrayList<String[]>(5);

        for (int i = 0; i < numberOfAggregates; i++) {
            String aggName = ((MolapDef.AggName) aggTables[i]).getNameAttribute();
            if (aggTableName.equals(aggName)) {
                MolapDef.AggMeasure[] aggMeasures = aggTables[i].measures;
                int numOfAgg = aggMeasures.length;

                String[] aggregators = new String[numOfAgg];
                String[] measureColumns = new String[numOfAgg];
                String[] measureNames = new String[numOfAgg];
                String[] aggregatorClass = new String[numOfAgg];
                String[] measureActualColumnName = new String[numOfAgg];

                for (int k = 0; k < numOfAgg; k++) {
                    measureColumns[k] = aggMeasures[k].column;
                    aggregators[k] = aggMeasures[k].aggregator;
                    measureNames[k] = aggMeasures[k].name;
                    measureActualColumnName[k] = aggMeasures[k].column;
                }
                cubeMeasures.add(measureColumns);
                cubeMeasures.add(aggregators);
                cubeMeasures.add(measureNames);
                cubeMeasures.add(aggregatorClass);
                cubeMeasures.add(measureActualColumnName);
                break;
            }
        }

        return cubeMeasures;

    }

    private static String[] getLevelsWithTableName(AggLevel[] levels, Cube cube, Schema schema,
            boolean appendFactTableNameIfRequired) {
        int size = levels.length;
        String[] resultLevels = new String[size];
        for (int i = 0; i < size; i++) {
            String name = levels[i].name;
            name = name.replace("]", "");
            name = name.replace("[", "");
            String[] split = name.split("\\.");
            // If only one hierachy exists.
            //[dimensionName].[levelName]
            if (split.length == 2) {
                resultLevels[i] = getDimensionTable(split[0], split[1], cube, schema,
                        appendFactTableNameIfRequired);
            }
            // If more than one hierarchy exists in the same Dimension then
            // [dimensionName].[hierarchyname].[levelName]
            else if (split.length > 2) {
                resultLevels[i] = getDimensionTable(split[0], split[1], split[2], cube, schema,
                        appendFactTableNameIfRequired);
            }

        }

        return resultLevels;
    }

    private static String getDimensionTable(String dimName, String hierName, String levelName,
            Cube cube, Schema schema, boolean appendFactTableNameIfRequired) {
        CubeDimension[] dimensions = cube.dimensions;

        for (CubeDimension dim : dimensions) {

            if (dimName.equals(dim.name)) {
                Hierarchy[] hierarchies = null;
                hierarchies = extractHierarchies(schema, dim);
                for (Hierarchy hierarchy : hierarchies) {
                    if (hierName.equals(hierarchy.name)) {
                        for (Level levels : hierarchy.levels) {
                            if (levels.parentname != null) continue;
                            if (levelName.equals(levels.name)) {
                                RelationOrJoin relation = hierarchy.relation;
                                dimName = dimName.replaceAll(" ", "_");
                                String tableName = relation == null ?
                                        appendFactTableNameIfRequired ?
                                                getFactTableName(cube) :
                                                dimName :
                                        ((Table) hierarchy.relation).name;
                                return tableName + '_' + levels.column;
                            }
                        }
                    }
                }
            }
        }

        return "";
    }

    private static String getDimensionTable(String dimName, String levelName, Cube cube,
            Schema schema, boolean appendFactTableNameIfRequired) {
        CubeDimension[] dimensions = cube.dimensions;

        for (CubeDimension dim : dimensions) {

            if (dimName.equals(dim.name)) {
                Hierarchy[] hierarchies = null;
                hierarchies = extractHierarchies(schema, dim);
                for (Hierarchy hierarchy : hierarchies) {
                    for (Level levels : hierarchy.levels) {
                        if (levels.parentname != null) continue;
                        if (levelName.equals(levels.name)) {
                            RelationOrJoin relation = hierarchy.relation;
                            dimName = dimName.replaceAll(" ", "_");
                            String tableName = relation == null ?
                                    appendFactTableNameIfRequired ?
                                            getFactTableName(cube) :
                                            dimName :
                                    ((Table) hierarchy.relation).name;
                            return tableName + '_' + levels.column;
                        }

                    }
                }
            }
        }

        return "";
    }

    /**
     * Make the properties string.
     * Level Entries separated by '&'
     * Level and prop details separated by ':'
     * Property column name and index separated by ','
     * Level:p1,index1:p2,index2&Level2....
     */
    public static int getPropertyString(CubeDimension[] dimensions, StringBuilder propStringBuilder,
            int counter, Schema schema) {
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                counter = generatePropertyString(propStringBuilder, counter, hierarchy);
            }
        }

        return counter;
    }

    private static int generatePropertyString(StringBuilder propString, int counter,
            Hierarchy hierarchy) {
        for (Level level : hierarchy.levels) {
            if (level.parentname != null) continue;
            boolean levelAdded = false;

            // First is ordinal column
            if (hasOrdinalColumn(level)) {
                if (!levelAdded) {
                    levelAdded = true;
                    propString.append(level.column);
                }
                propString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                propString.append(level.ordinalColumn);
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append(counter++);
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append("integer");
            }

            // Second is name column
            if (level.nameColumn != null && !"".equals(level.nameColumn)) {
                if (!levelAdded) {
                    levelAdded = true;
                    propString.append(level.column);
                }
                propString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                propString.append(level.nameColumn);
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append(counter++);
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append("text");

            }

            // Next all properties
            for (Property property : level.properties) {
                if (!levelAdded) {
                    levelAdded = true;
                    propString.append(level.column);
                }
                propString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                propString.append(property.column);
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append(counter++);
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append(MolapMetadata.getDBDataType(property.type, true));
            }
            if (levelAdded) {
                propString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }
        }
        return counter;
    }

    /**
     * getHeirAndCardinalityString
     *
     * @param dimensions
     * @param schema
     * @return String
     */
    public static String getHeirAndCardinalityString(CubeDimension[] dimensions, Schema schema) {
        StringBuilder builder = new StringBuilder();
        String heirName = null;
        List<Integer> cardinalityList =
                new ArrayList<Integer>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            String dimName = cDimension.name;
            for (Hierarchy hierarchy : hierarchies) {
                dimName = dimName.replaceAll(" ", "_");
                heirName = hierarchy.name;
                if (heirName == null || "".equals(heirName.trim())) {
                    heirName = cDimension.name;
                }
                heirName = heirName.replaceAll(" ", "_");
                builder.append(dimName + '_' + heirName + ".hierarchy");
                for (Level level : hierarchy.levels) {
                    if (level.parentname != null) continue;
                    cardinalityList.add(level.levelCardinality);
                }
                for (int i = 0; i < cardinalityList.size(); i++) {
                    builder.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                    builder.append(cardinalityList.get(i));
                }
                builder.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
                cardinalityList = new ArrayList<Integer>(MolapCommonConstants.CONSTANT_SIZE_TEN);
            }
        }
        return builder.toString();
    }

    public static String getMetaHeirString(CubeDimension[] dimensions, Schema schema,
            String factTableName) {
        StringBuilder propString = new StringBuilder();

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                propString.append(perpareMetaHeirString(cDimension, hierarchy, factTableName,
                        dimensions, schema));
                int lastIndexOf = propString.lastIndexOf(MolapCommonConstants.COLON_SPC_CHARACTER);
                propString.delete(lastIndexOf,
                        lastIndexOf + MolapCommonConstants.COLON_SPC_CHARACTER.length());
                propString.append(MolapCommonConstants.HASH_SPC_CHARACTER);
                String type = null;
                if (cDimension instanceof Dimension) {
                    type = ((MolapDef.Dimension) cDimension).type;
                } else if (cDimension instanceof DimensionUsage) {
                    String sourceDimensionName = ((DimensionUsage) cDimension).source;
                    Dimension[] schemaGlobalDimensions = schema.dimensions;
                    for (Dimension dimension : schemaGlobalDimensions) {
                        if (sourceDimensionName.equals(dimension.name)) {
                            type = ((MolapDef.Dimension) dimension).type;
                        }
                    }
                }
                if (DimensionType.TimeDimension.name().equals(type)) {
                    propString.append(true);
                    propString.append(levelTypeColumnString(hierarchy));
                    lastIndexOf = propString.lastIndexOf(MolapCommonConstants.STAR_SPC_CHARACTER);
                    propString.delete(lastIndexOf,
                            lastIndexOf + MolapCommonConstants.STAR_SPC_CHARACTER.length());
                } else {
                    propString.append(false);
                    propString.append(MolapCommonConstants.SEMICOLON_SPC_CHARACTER);
                    propString.append("");

                }

                propString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }
        }

        // Delete the last & character
        String prop = propString.toString();
        if (prop.endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            prop = prop.substring(0,
                    prop.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER.length());
        }
        return prop;
    }

    private static String levelTypeColumnString(Hierarchy hierarchy) {
        StringBuilder propString = new StringBuilder();
        boolean isFirst = true;

        propString.append(MolapCommonConstants.SEMICOLON_SPC_CHARACTER);
        for (Level level : hierarchy.levels) {
            if (level.parentname != null) continue;
            String levelType = level.levelType;
            if (LevelType.TimeYears.name().equals(levelType)) {
                propString.append("YEAR" + MolapCommonConstants.COMA_SPC_CHARACTER + level.column
                        + MolapCommonConstants.STAR_SPC_CHARACTER);
            } else if (LevelType.TimeMonths.name().equals(levelType)) {
                propString.append("MONTHS" + MolapCommonConstants.COMA_SPC_CHARACTER + level.column
                        + MolapCommonConstants.STAR_SPC_CHARACTER);
            } else if (LevelType.TimeDays.name().equals(levelType)) {
                propString.append("DAYS" + MolapCommonConstants.COMA_SPC_CHARACTER + level.column
                        + MolapCommonConstants.STAR_SPC_CHARACTER);
            } else if (LevelType.TimeHours.name().equals(levelType) || LevelType.TimeMinutes.name()
                    .equals(levelType)) {
                if (isFirst) {
                    propString.append(MolapCommonConstants.STAR_SPC_CHARACTER);
                    isFirst = false;
                }
            }

        }
        return propString.toString();
    }

    private static String perpareMetaHeirString(CubeDimension dimension, Hierarchy hierarchy,
            String factTableName, CubeDimension[] dimensions, Schema schema) {
        StringBuilder propString = new StringBuilder();
        RelationOrJoin relation = hierarchy.relation;
        String dimName = dimension.name;
        dimName = dimName.replaceAll(" ", "_");

        String tableName = relation == null ? factTableName : ((Table) hierarchy.relation).name;
        if (hierarchy.name != null) {
            String hierName = hierarchy.name.replaceAll(" ", "_");
            propString.append(dimName + '_' + hierName);
        } else {
            propString.append(dimName + '_' + dimName);
        }

        propString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
        for (Level level : hierarchy.levels) {
            if (level.parentname != null) continue;
            propString.append(tableName + '_' + level.column);

            // First is ordinal column
            if (hasOrdinalColumn(level)) {
                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                propString.append(tableName + '_' + level.ordinalColumn);

            }

            // Add the Name column if present.
            addNameColumnsAndProperty(tableName, level.column, dimensions, schema, propString,
                    factTableName);
            propString.append(MolapCommonConstants.COLON_SPC_CHARACTER);

        }
        String prop = propString.toString();
        return prop;
    }

    /**
     * This method will add the name column and property in the output string.
     *
     * @param tableName
     * @param levelColumn
     * @param dimensions
     * @param schema
     * @param propString
     */
    private static void addNameColumnsAndProperty(String tableName, String levelColumn,
            CubeDimension[] dimensions, Schema schema, StringBuilder propString,
            String factTableName) {
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                String dimTableName =
                        relation == null ? factTableName : ((Table) hierarchy.relation).name;
                if (tableName.equals(dimTableName)) {
                    for (Level level : hierarchy.levels) {
                        if (level.parentname != null) continue;
                        if (levelColumn.equals(level.column)) {
                            if (level.nameColumn != null && !""
                                    .equals(tableName + '_' + level.nameColumn)) {
                                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                                propString.append(tableName + '_' + level.nameColumn);
                            }

                            for (Property property : level.properties) {
                                propString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                                propString.append(tableName + '_' + property.column);
                            }
                        }
                    }
                }

            }
        }
    }

    /**
     * Check whether to consider Ordinal column separately if it is configured.
     */
    private static boolean hasOrdinalColumn(Level level) {
        return (null != level.ordinalColumn && !level.column.equals(level.ordinalColumn));
    }

    public static String getTableNameString(String factTableName, CubeDimension[] dimensions,
            Schema schema) {
        StringBuffer stringBuffer = new StringBuffer();

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            String dimName = cDimension.name;
            dimName = dimName.replaceAll(" ", "_");
            for (Hierarchy hierarchy : hierarchies) {
                String hierName = hierarchy.name;
                if (null == hierName || "".equals(hierName.trim())) {
                    hierName = dimName;
                }
                RelationOrJoin relation = hierarchy.relation;
                hierName = hierName.replaceAll(" ", "_");

                String tableName =
                        relation == null ? factTableName : ((Table) hierarchy.relation).name;
                stringBuffer.append(dimName + '_' + hierName);
                stringBuffer.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                stringBuffer.append(tableName);
                stringBuffer.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }
        }
        // Delete the last & character
        String string = stringBuffer.toString();
        if (string.endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            string = string.substring(0,
                    string.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER.length());
        }
        return string;
    }

    public static String getMdkeySizeForFact(CubeDimension[] dimensions, Schema schema) {
        int[] dims = getDimsArray(dimensions, schema);
        return KeyGeneratorFactory.getKeyGenerator(dims).getKeySizeInBytes() + "";
    }

    private static int[] getDimsArray(CubeDimension[] dimensions, Schema schema) {
        List<Integer> cardinalityList =
                new ArrayList<Integer>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                if (hierarchy.normalized) {
                    Level[] levels1 = hierarchy.levels;
                    cardinalityList.add(levels1[levels1.length - 1].levelCardinality);
                } else {
                    for (Level level : hierarchy.levels) {
                        if (level.parentname != null) continue;
                        cardinalityList.add(level.levelCardinality);
                    }
                }
            }
        }
        int[] dims = new int[cardinalityList.size()];
        for (int i = 0; i < cardinalityList.size(); i++) {
            dims[i] = cardinalityList.get(i);
        }
        return dims;
    }

    public static String getMdkeySizeForAgg(String[] dimensions,
            Map<String, String> dimCardinalities) {
        int[] dims = new int[dimensions.length];
        for (int i = 0; i < dimensions.length; i++) {
            dims[i] = Integer.parseInt(dimCardinalities.get(dimensions[i]));
        }
        return KeyGeneratorFactory.getKeyGenerator(dims).getKeySizeInBytes() + "";

    }

    public static String getHeirAndKeySizeMapForFact(CubeDimension[] dimensions, Schema schema) {
        StringBuffer stringBuffer = new StringBuffer();
        List<Integer> cardinalityList = null;
        String heirName = null;
        int[] dims = null;
        int keySizeInBytes = 0;
        for (CubeDimension cDimension : dimensions) {
            cardinalityList = new ArrayList<Integer>(MolapCommonConstants.CONSTANT_SIZE_TEN);
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            String dimName = cDimension.name;
            for (Hierarchy hierarchy : hierarchies) {
                dimName = dimName.replaceAll(" ", "_");
                heirName = hierarchy.name;
                if (heirName == null || "".equals(heirName.trim())) {
                    heirName = dimName;
                }
                heirName = heirName.replaceAll(" ", "_");
                for (Level level : hierarchy.levels) {
                    if (level.parentname != null) continue;
                    cardinalityList.add(level.levelCardinality);
                }
                dims = new int[cardinalityList.size()];
                for (int i = 0; i < cardinalityList.size(); i++) {
                    dims[i] = cardinalityList.get(i);
                }
                keySizeInBytes = KeyGeneratorFactory.getKeyGenerator(dims).getKeySizeInBytes();
                stringBuffer.append(dimName + '_' + heirName
                        + MolapCommonConstants.HIERARCHY_FILE_EXTENSION
                        + MolapCommonConstants.COLON_SPC_CHARACTER + keySizeInBytes
                        + MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
                cardinalityList.clear();
            }
        }
        return stringBuffer.toString();
    }

    public static String getHierarchyStringWithColumnNames(CubeDimension[] dimensions,
            Schema schema) {

        StringBuilder hierString = new StringBuilder();
        String hierStr = "";
        int lengthOfLevels = 0;

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            String dimName = cDimension.name;

            for (Hierarchy hierarchy : hierarchies) {
                String hName = hierarchy.name;
                if (hName == null || "".equals(hName.trim())) {
                    hName = dimName;
                }

                // Replace the hierarchy name space with "_"
                hName = hName.replaceAll(" ", "_");

                dimName = dimName.replaceAll(" ", "_");

                lengthOfLevels = hierarchy.levels.length;
                int hierlength = hierarchy.levels.length;
                if (hierlength > 0) {
                    StringBuilder localString = new StringBuilder();

                    for (Level level : hierarchy.levels) {
                        if (level.parentname != null) continue;

                        localString.append(level.column);

                        if (lengthOfLevels > 1) {
                            localString.append(MolapCommonConstants.COMA_SPC_CHARACTER);

                        }
                        lengthOfLevels--;
                    }
                    localString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
                    hierStr = localString.toString();
                    hierStr = dimName + '_' + hName + MolapCommonConstants.COLON_SPC_CHARACTER
                            + hierStr;
                    hierString.append(hierStr);
                }
            }
        }

        hierStr = hierString.toString();
        if (hierStr.length() > 0 && hierStr
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            hierStr = hierStr.substring(0,
                    hierStr.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER.length());
        }
        return hierStr;

    }

    /**
     * Return foreign key array
     *
     * @param dimensions
     * @return
     */
    public static String[] getForeignKeyForTables(CubeDimension[] dimensions, Schema schema) {
        Set<String> foreignKey = new LinkedHashSet<String>();
        for (CubeDimension cDimension : dimensions) {
            Dimension dimension = null;
            if (cDimension instanceof DimensionUsage) {
                String sourceDimensionName = ((DimensionUsage) cDimension).source;
                Dimension[] schemaGlobalDimensions = schema.dimensions;
                for (Dimension dim : schemaGlobalDimensions) {
                    if (sourceDimensionName.equals(dim.name)) {
                        dimension = dim;
                    }
                }
            } else {
                dimension = (Dimension) cDimension;
            }
            if (null != dimension) {
                foreignKey.add(dimension.foreignKey);
            }

        }
        return foreignKey.toArray(new String[foreignKey.size()]);
    }

    /**
     * Return foreign key and respective hierarchy String.
     *
     * @param dimensions
     * @return
     */
    public static String getForeignKeyHierarchyString(CubeDimension[] dimensions, Schema schema,
            String factTable) {
        StringBuilder foreignKeyHierarchyString = new StringBuilder();
        String columns = "";

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            String dimName = cDimension.name;

            for (Hierarchy hierarchy : hierarchies) {
                String foreignKey = cDimension.foreignKey;

                String hierName = hierarchy.name;
                if (null == hierName) {
                    hierName = dimName;
                }
                hierName = hierName.replaceAll(" ", "_");

                dimName = dimName.replaceAll(" ", "_");

                RelationOrJoin relation = hierarchy.relation;

                String tableName = relation == null ? dimName : ((Table) hierarchy.relation).name;

                if (tableName.equals(factTable)) {
                    continue;
                }

                foreignKeyHierarchyString.append(foreignKey);
                foreignKeyHierarchyString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                foreignKeyHierarchyString.append(dimName + '_' + hierName);
                foreignKeyHierarchyString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }
        }
        columns = foreignKeyHierarchyString.toString();
        if (columns.length() > 0 && columns
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            columns = columns.substring(0,
                    columns.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER.length());
        }
        return columns;

    }

    /**
     * Return foreign key and respective hierarchy String.
     *
     * @param dimensions
     * @param factTableName
     * @return
     */
    public static String getForeignKeyAndPrimaryKeyMapString(CubeDimension[] dimensions,
            Schema schema, String factTableName) {
        StringBuilder foreignKeyHierarchyString = new StringBuilder();
        String columns = "";

        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);

            for (Hierarchy hierarchy : hierarchies) {
                String foreignKey = cDimension.foreignKey;

                RelationOrJoin relation = hierarchy.relation;
                String dimName = cDimension.name;
                dimName = dimName.replaceAll(" ", "_");

                String hierName = hierarchy.name;
                if (null != hierName) {
                    hierName = hierName.replaceAll(" ", "_");
                } else {
                    hierName = dimName;
                }

                String tableName = relation == null ? dimName : ((Table) hierarchy.relation).name;

                if (tableName.equals(factTableName)) {
                    continue;
                }

                String primaryKey = hierarchy.primaryKey;

                if (null == foreignKey || null == primaryKey) {
                    continue;
                }

                foreignKeyHierarchyString.append(foreignKey);
                foreignKeyHierarchyString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                foreignKeyHierarchyString.append(tableName + '_' + primaryKey);
                foreignKeyHierarchyString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }
        }
        columns = foreignKeyHierarchyString.toString();
        if (columns.length() > 0 && columns
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            columns = columns.substring(0,
                    columns.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER.length());
        }
        return columns;

    }

    /**
     * Return foreign key array
     *
     * @param dimensions
     * @return
     */
    public static String getPrimaryKeyString(CubeDimension[] dimensions, Schema schema) {
        StringBuffer primaryKeyStringbuffer = new StringBuffer();
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);

            for (Hierarchy hierarchy : hierarchies) {
                String primaryKey = hierarchy.primaryKey;

                RelationOrJoin relation = hierarchy.relation;
                String dimName = cDimension.name;
                dimName = dimName.replaceAll(" ", "_");

                String tableName = relation == null ? dimName : ((Table) hierarchy.relation).name;

                primaryKeyStringbuffer.append(tableName + '_' + primaryKey);
                primaryKeyStringbuffer.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }

        }

        String primaryKeyString = primaryKeyStringbuffer.toString();

        if (primaryKeyString.length() > 0 && primaryKeyString
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            primaryKeyString = primaryKeyString.substring(0,
                    primaryKeyString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }

        return primaryKeyString;
    }

    /**
     * Get Measure Name String
     *
     * @param cube
     * @return
     */
    public static String getMeasuresNamesString(Cube cube) {
        Measure[] measures = cube.measures;
        StringBuilder measureNames = new StringBuilder();

        for (int i = 0; i < measures.length; i++) {
            measureNames.append(measures[i].name);
            measureNames.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
        }

        String measureNameString = measureNames.toString();

        if (measureNameString.length() > 0 && measureNameString
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            measureNameString = measureNameString.substring(0,
                    measureNameString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }

        return measureNameString;
    }

    /**
     * Get Measure Name String
     *
     * @param cube
     * @return
     */
    public static String getMeasuresUniqueColumnNamesString(Cube cube) {
        Measure[] measures = cube.measures;
        StringBuilder measureNames = new StringBuilder();
        Set<String> set = new HashSet<String>(MolapCommonConstants.DEFAULT_COLLECTION_SIZE);
        for (int i = 0; i < measures.length; i++) {
            if (!set.contains(measures[i].column)) {
                set.add(measures[i].column);
                measureNames.append(measures[i].column);
                measureNames.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
            }
        }
        String measureNameString = measureNames.toString();
        if (measureNameString.length() > 0 && measureNameString
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            measureNameString = measureNameString.substring(0,
                    measureNameString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }
        return measureNameString;
    }

    /**
     * Get Measure Name String
     */
    public static String getMeasuresNamesStringForAgg(String[] measures) {
        StringBuilder measureNames = new StringBuilder();

        for (int i = 0; i < measures.length; i++) {
            measureNames.append(measures[i]);
            measureNames.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
        }

        String measureNameString = measureNames.toString();

        if (measureNameString.endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            measureNameString = measureNameString.substring(0,
                    measureNameString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }

        return measureNameString;
    }

    /**
     * Get Measure Aggregator array
     *
     * @param cube
     * @return
     */
    public static String[] getMeasuresAggragatorArray(Cube cube) {
        Measure[] measures = cube.measures;
        String[] msrAggregators = new String[measures.length];

        for (int i = 0; i < msrAggregators.length; i++) {
            msrAggregators[i] = measures[i].aggregator;
        }

        return msrAggregators;
    }

    public static String getActualDimensions(SchemaInfo schemaInfo, Cube cube, Schema schema) {
        StringBuilder actualDim = new StringBuilder();
        MolapDef.CubeDimension[] dimensions = cube.dimensions;
        for (CubeDimension cDimension : dimensions) {

            if (cDimension.highCardinality) {
                continue;
            }

            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);

            for (Hierarchy hierarchy : hierarchies) {
                for (Level level : hierarchy.levels) {
                    if (level.parentname != null) continue;
                    actualDim.append(level.column);
                    actualDim.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
                }

            }
        }

        String actualDimString = actualDim.toString();

        if (actualDimString.length() > 0 && actualDimString
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            actualDimString = actualDimString.substring(0,
                    actualDimString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }

        return actualDimString;
    }

    public static String getActualDimensionsForAggregate(String[] columns) {
        StringBuilder actualDim = new StringBuilder();
        for (String column : columns) {
            actualDim.append(column);
            actualDim.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
        }

        String actualDimString = actualDim.toString();

        if (actualDimString.endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            actualDimString = actualDimString.substring(0,
                    actualDimString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }

        return actualDimString;
    }

    public static String getNormHiers(Cube cube, Schema schema) {
        StringBuilder normHier = new StringBuilder();
        MolapDef.CubeDimension[] dimensions = cube.dimensions;
        for (CubeDimension cDimension : dimensions) {
            //
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                if (hierarchy.normalized) {
                    normHier.append(hierarchy.name);
                    normHier.append(MolapCommonConstants.COMA_SPC_CHARACTER);
                }

            }
        }

        String normHierString = normHier.toString();

        if (normHierString.length() > 0 && normHierString
                .endsWith(MolapCommonConstants.COMA_SPC_CHARACTER)) {
            normHierString = normHierString.substring(0,
                    normHierString.length() - MolapCommonConstants.COMA_SPC_CHARACTER.length());
        }

        return normHierString;

    }

    public static String getMeasuresDataType(Cube cube) {
        StringBuilder measureDataTypeString = new StringBuilder();
        MolapDef.Measure[] measures = cube.measures;

        for (MolapDef.Measure measure : measures) {
            Annotations annotations = measure.annotations;
            if (null == annotations) {
                continue;
            }
            Annotation[] array = measure.annotations.array;
            for (int i = 0; i < array.length; i++) {
                if (array[i].name.equals(MolapCommonConstants.MEASURE_SRC_DATA_TYPE)) {
                    measureDataTypeString.append(measure.column);
                    measureDataTypeString.append(MolapCommonConstants.COLON_SPC_CHARACTER);
                    if (array[i].cdata.equalsIgnoreCase("String")) {
                        if (isMeasureColumnAsSomeOtherAggregator(measure.column, cube)) {
                            measureDataTypeString.append("false");
                        } else {
                            measureDataTypeString.append("true");
                        }
                    } else {
                        measureDataTypeString.append("false");
                    }
                    measureDataTypeString.append(MolapCommonConstants.AMPERSAND_SPC_CHARACTER);
                }
            }
        }

        String measureTypeString = measureDataTypeString.toString();

        if (measureTypeString.length() > 0 && measureTypeString
                .endsWith(MolapCommonConstants.AMPERSAND_SPC_CHARACTER)) {
            measureTypeString = measureTypeString.substring(0,
                    measureTypeString.length() - MolapCommonConstants.AMPERSAND_SPC_CHARACTER
                            .length());
        }

        return measureTypeString;

    }

    private static boolean isMeasureColumnAsSomeOtherAggregator(String msrColumnName, Cube cube) {
        Measure[] measures = cube.measures;
        for (Measure measure : measures) {
            if (msrColumnName.equals(measure.column)) {
                Annotations annotations = measure.annotations;
                if (null == annotations) {
                    String aggregator = measure.aggregator;
                    if (aggregator.equals("count") || aggregator.equals("distinct-count")) {
                        continue;
                    } else {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static Map<String, Integer> getLevelOrdinals(Cube cube, Schema schema) {
        Map<String, Integer> ordinalMap =
                new HashMap<String, Integer>(MolapCommonConstants.DEFAULT_COLLECTION_SIZE);
        int count = 0;
        Hierarchy[] hierarchies = null;
        for (CubeDimension dim : cube.dimensions) {
            hierarchies = extractHierarchies(schema, dim);
            for (Hierarchy hier : hierarchies) {
                for (Level level : hier.levels) {
                    if (level.parentname != null) continue;
                    ordinalMap.put(dim.name + '_' + hier.name + '_' + level.name, count++);
                }
            }
        }
        return ordinalMap;
    }

    /**
     * Below method will be used to get the level and its data type string
     *
     * @param dimensions
     * @param schema
     * @param cube
     * @return String
     */
    public static String getLevelAndDataTypeMapString(CubeDimension[] dimensions, Schema schema,
            Cube cube) {
        StringBuilder dimString = new StringBuilder();
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;

                String tableName = relation == null ?
                        getFactTableName(cube) :
                        ((Table) hierarchy.relation).name;

                for (Level level : hierarchy.levels) { // Added for Normalized hierarchy AR-UniBI-OLAP-003
                    String levelName = tableName + '_' + level.column;
                    dimString.append(levelName + MolapCommonConstants.LEVEL_FILE_EXTENSION
                            + MolapCommonConstants.COLON_SPC_CHARACTER + level.type
                            + MolapCommonConstants.HASH_SPC_CHARACTER);
                }
            }
        }
        return dimString.toString();
    }

    /**
     * Below method will be used to get the level and its data type string
     *
     * @param schema
     * @param cube
     * @return String
     */
    public static String getLevelDataTypeAndParentMapString(Cube cube, Schema schema) {
        StringBuilder dimString = new StringBuilder();
        MolapDef.CubeDimension[] dimensions = cube.dimensions;
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                if (hierarchy.levels.length > 1 && (hierarchy.levels[0].type.equals("Array")
                        || hierarchy.levels[0].type.equals("Struct"))) {
                    Level levelZero = hierarchy.levels[0];
                    boolean isFirst = true;
                    dimString.append(levelZero.name + MolapCommonConstants.COLON_SPC_CHARACTER
                            + levelZero.type + MolapCommonConstants.COLON_SPC_CHARACTER + ""
                            + MolapCommonConstants.HASH_SPC_CHARACTER);
                    for (Level level : hierarchy.levels) {
                        if (isFirst) {
                            isFirst = false;
                            continue;
                        }
                        dimString.append(level.name + MolapCommonConstants.COLON_SPC_CHARACTER
                                + level.type + MolapCommonConstants.COLON_SPC_CHARACTER
                                + level.parentname + MolapCommonConstants.HASH_SPC_CHARACTER);
                    }
                    dimString.append(MolapCommonConstants.SEMICOLON_SPC_CHARACTER);
                }
            }
        }
        return dimString.toString();
    }

    /**
     * Below method is to get the dimension
     *
     * @param dims
     * @param dimensionName
     * @return Dimension
     */
    public static Dimension findDimension(CubeDimension[] dims, String dimensionName) {
        for (CubeDimension cDimension : dims) {
            if (cDimension.name.equals(dimensionName)) {
                return (Dimension) cDimension;
            }
        }
        return null;
    }

    /**
     * Get dimension string from a array of CubeDimension,which can be shared
     * CubeDimension within schema or in a cube.
     *
     * @param cube
     * @param dimensions
     * @return
     */
    public static int getHighCardinalityDimensionString(Cube cube, CubeDimension[] dimensions,
            StringBuilder dimString, int counter, Schema schema) {
        for (CubeDimension cDimension : dimensions) {
            if (!cDimension.highCardinality) {
                continue;
            }
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);

            for (Hierarchy hierarchy : hierarchies) {
                RelationOrJoin relation = hierarchy.relation;
                String tableName = relation == null ?
                        getFactTableName(cube) :
                        ((Table) hierarchy.relation).name;
                int i = hierarchy.levels.length;

                for (Level level : hierarchy.levels) { //Added for Normalized hierarchy AR-UniBI-OLAP-003

                    if (hierarchy.normalized) {
                        if (i == 1) {
                            dimString.append(tableName + '_' + level.column
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + counter
                                    + MolapCommonConstants.COLON_SPC_CHARACTER
                                    + level.levelCardinality
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + 'Y');

                        } else {
                            dimString.append(tableName + '_' + level.column
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + counter
                                    + MolapCommonConstants.COLON_SPC_CHARACTER
                                    + level.levelCardinality
                                    + MolapCommonConstants.COLON_SPC_CHARACTER + 'N');
                        }
                        if (i > 1) {
                            dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);

                        }
                        counter++;
                    } else {

                        dimString.append(tableName + '_' + level.column
                                + MolapCommonConstants.COLON_SPC_CHARACTER + counter
                                + MolapCommonConstants.COLON_SPC_CHARACTER + level.levelCardinality
                                + MolapCommonConstants.COLON_SPC_CHARACTER + 'Y');
                        counter++;
                        if (i > 1) {
                            dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);

                        }

                    }

                    i--;

                }
                dimString.append(MolapCommonConstants.COMA_SPC_CHARACTER);
            }
        }

        return counter;
    }

    /**
     * getting all the dimensions irrespective of the high cardinality dimensions.
     *
     * @param cube
     * @return
     */
    public static String[] getAllCubeDimensions(Cube cube, Schema schema) {
        List<String> list = new ArrayList<String>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        MolapDef.CubeDimension[] dimensions = cube.dimensions;
        for (CubeDimension cDimension : dimensions) {
            Hierarchy[] hierarchies = null;
            hierarchies = extractHierarchies(schema, cDimension);
            for (Hierarchy hierarchy : hierarchies) {
                String factTableName = getFactTableName(cube);
                list.addAll(getTableNames(factTableName, hierarchy));
            }
        }
        String[] fields = new String[list.size()];
        fields = list.toArray(fields);
        return fields;
    }

}