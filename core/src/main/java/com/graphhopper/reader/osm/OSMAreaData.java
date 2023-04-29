/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.reader.osm;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.graphhopper.coll.GHLongIntBTree;
import com.graphhopper.coll.LongIntMap;
import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.storage.Directory;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.openjdk.jol.info.GraphLayout;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class OSMAreaData {
    private final List<OSMArea> osmAreas;
    private final LongIntMap nodeIndexByOSMNodeId;
    private final PillarInfo coordinates;

    public OSMAreaData(Directory directory) {
        osmAreas = new ArrayList<>();
        nodeIndexByOSMNodeId = new GHLongIntBTree(200);
        coordinates = new PillarInfo(false, directory, "_osm_area");
    }

    public void addArea(Map<String, Object> tags, LongArrayList nodes) {
        osmAreas.add(new OSMArea(tags, nodes));
        for (LongCursor node : nodes)
            if (nodeIndexByOSMNodeId.get(node.value) < 0)
                nodeIndexByOSMNodeId.put(node.value, Math.toIntExact(nodeIndexByOSMNodeId.getSize()));
    }

    public void setCoordinate(long osmNodeId, double lat, double lon) {
        int nodeIndex = nodeIndexByOSMNodeId.get(osmNodeId);
        if (nodeIndex >= 0)
            coordinates.setNode(nodeIndex, lat, lon);
    }

    public List<CustomArea> buildOSMAreas() {
        // todonow: remove later
        System.out.println(GraphLayout.parseInstance(this).toFootprint());
        System.out.println(GraphLayout.parseInstance(osmAreas).toFootprint());
        System.out.println(GraphLayout.parseInstance(nodeIndexByOSMNodeId).toFootprint());
        System.out.println(GraphLayout.parseInstance(coordinates).toFootprint());
        AtomicInteger invalidGeometries = new AtomicInteger();
        GeometryFactory geometryFactory = new GeometryFactory();
        List<CustomArea> result = osmAreas.stream().map(a -> {
                    Coordinate[] cs = new Coordinate[a.nodes.size()];
                    for (LongCursor node : a.nodes) {
                        int nodeIndex = nodeIndexByOSMNodeId.get(node.value);
                        cs[node.index] = new Coordinate(coordinates.getLon(nodeIndex), coordinates.getLat(nodeIndex));
                    }
                    try {
                        List<Polygon> polygons = Collections.singletonList(geometryFactory.createPolygon(new PackedCoordinateSequence.Double(cs, 2)));
                        return new CustomArea(a.tags, polygons);
                    } catch (IllegalArgumentException e) {
                        // todonow: apparently, some areas do not form a closed ring or something, looks like these are tagging errors in OSM!
                        invalidGeometries.incrementAndGet();
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        System.out.println("Warning: There were " + invalidGeometries.get() + " invalid geometries among the " + osmAreas.size() + " osm areas");
        return result;
    }

    public static class OSMArea {
        Map<String, Object> tags;
        LongArrayList nodes;

        public OSMArea(Map<String, Object> tags, LongArrayList nodes) {
            this.tags = tags;
            this.nodes = nodes;
        }
    }
}
