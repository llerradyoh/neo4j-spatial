/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.osm;

import static java.util.Arrays.asList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.geotools.referencing.datum.DefaultEllipsoid;
import org.neo4j.gis.spatial.Constants;
import org.neo4j.gis.spatial.SpatialDatabaseService;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Traverser;
import org.neo4j.graphdb.Traverser.Order;
import org.neo4j.graphdb.index.BatchInserterIndex;
import org.neo4j.graphdb.index.BatchInserterIndexProvider;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.index.bdbje.BerkeleyDbBatchInserterIndex;
import org.neo4j.index.bdbje.BerkeleyDbBatchInserterIndexProvider;
import org.neo4j.index.impl.lucene.LuceneBatchInserterIndexProvider;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.impl.batchinsert.BatchInserter;
import org.neo4j.kernel.impl.batchinsert.BatchInserterImpl;
import org.neo4j.kernel.impl.batchinsert.SimpleRelationship;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.vividsolutions.jts.geom.Envelope;

public class OSMImporter implements Constants {
    public static DefaultEllipsoid WGS84 = DefaultEllipsoid.WGS84;

    //TODO: is this really needed? Should we have several indexes?
    protected static final List<String> NODE_INDEXING_KEYS = new ArrayList<String>();
    static {
        NODE_INDEXING_KEYS.add("node_osm_id");
    }

    protected boolean nodesProcessingFinished = false;
    private String layerName;
    private StatsManager stats = new StatsManager();
    private long osm_dataset = -1;
    
	private static class TagStats {
		private String name;
		private int count = 0;
		private HashMap<String, Integer> stats = new HashMap<String, Integer>();

		TagStats(String name) {
			this.name = name;
		}

		int add(String key) {
			count++;
			if (stats.containsKey(key)) {
				int num = stats.get(key);
				stats.put(key, ++num);
				return num;
			} else {
				stats.put(key, 1);
				return 1;
			}
		}

		/**
		 * Return only reasonably commonly used tags.
		 * 
		 * @return
		 */
		public String[] getTags() {
			if (stats.size() > 0) {
				int threshold = count / (stats.size() * 20);
				ArrayList<String> tags = new ArrayList<String>();
				for (String key : stats.keySet()) {
					if(key.equals("waterway")){
						System.out.println("debug["+key+"]: "+stats.get(key));
					}
					if (stats.get(key) > threshold)
						tags.add(key);
				}
				Collections.sort(tags);
				return tags.toArray(new String[tags.size()]);
			} else {
				return new String[0];
			}
		}

		public String toString() {
			return "TagStats[" + name + "]: " + asList(getTags());
		}
	}

	private static class StatsManager {
		private HashMap<String,TagStats> tagStats = new HashMap<String,TagStats>();
		private HashMap<Integer,Integer> geomStats = new HashMap<Integer,Integer>();;

		protected TagStats getTagStats(String type) {
	    	if(!tagStats.containsKey(type)) {
	    		tagStats.put(type,new TagStats(type));
	    	}
	    	return tagStats.get(type);
	    }

	    protected int addToTagStats(String type, String key) {
	    	getTagStats("all").add(key);
	    	return getTagStats(type).add(key);
	    }

	    protected int addToTagStats(String type, Collection<String> keys) {
	    	int count = 0;
	    	for(String  key:keys) {
	    		count += addToTagStats(type, key);
	    	}
	    	return count;
	    }

	    protected void printTagStats() {
			System.out.println("Tag statistics for " + tagStats.size() + " types:");
			for (String key : tagStats.keySet()) {
				TagStats stats = tagStats.get(key);
				System.out.println("\t" + key + ": " + stats);
			}
		}		

		protected void addGeomStats(Node geomNode) {
			if (geomNode != null) {
				addGeomStats((Integer) geomNode.getProperty(PROP_TYPE, null));
			}
		}
		
		protected void addGeomStats(Integer geom) {
			Integer count = geomStats.get(geom);
			geomStats.put(geom, count == null ? 1 : count + 1);
		}
		
		protected void dumpGeomStats() {
			System.out.println("Geometry statistics for " + geomStats.size() + " geometry types:");
			for (Object key : geomStats.keySet()) {
				Integer count = geomStats.get(key);
				System.out.println("\t" + SpatialDatabaseService.convertGeometryTypeToName((Integer)key) + ": " + count);
			}
			geomStats.clear();
		}

	}

	public OSMImporter(String layerName) {
        this.layerName = layerName;
    }

	public void reIndex(GraphDatabaseService database, int commitInterval) {
		reIndex(database, commitInterval, true, false);
	}

    public void reIndex(GraphDatabaseService database, int commitInterval, boolean includePoints, boolean includeRelations) {
        if (commitInterval < 1)
            throw new IllegalArgumentException("commitInterval must be >= 1");
		System.out.println("Re-indexing with GraphDatabaseService: " + database + " (class: " + database.getClass() + ")");

        setLogContext("Index");
        SpatialDatabaseService spatialDatabase = new SpatialDatabaseService(database);
        OSMLayer layer = (OSMLayer)spatialDatabase.getOrCreateLayer(layerName, OSMGeometryEncoder.class, OSMLayer.class);
		// TODO: The next line creates the relationship between the dataset and
		// layer, but this seems more like a side-effect and should be done
		// explicitly
		layer.getDataset(osm_dataset);
        layer.clear();  // clear the index without destroying underlying data

        long startTime = System.currentTimeMillis();
        Traverser traverser = database.getNodeById(osm_dataset).traverse(Order.DEPTH_FIRST, StopEvaluator.END_OF_GRAPH,
                ReturnableEvaluator.ALL_BUT_START_NODE, OSMRelation.WAYS, Direction.OUTGOING, OSMRelation.NEXT,
                Direction.OUTGOING);
        Transaction tx = database.beginTx();
        int count = 0;
        try {
            layer.setExtraPropertyNames(stats.getTagStats("all").getTags());
            for (Node way : traverser) {
                incrLogContext();
				stats.addGeomStats(layer.addWay(way, true));
				if(includePoints) {
					Node first = way.getSingleRelationship(OSMRelation.FIRST_NODE, Direction.OUTGOING).getEndNode();
					for (Node proxy : first.traverse(Order.DEPTH_FIRST, StopEvaluator.END_OF_GRAPH, ReturnableEvaluator.ALL,
							OSMRelation.NEXT, Direction.OUTGOING)) {
						Node node = proxy.getSingleRelationship(OSMRelation.NODE, Direction.OUTGOING).getEndNode();
						stats.addGeomStats(layer.addWay(node, true));
					}
				}
                if (++count % commitInterval == 0) {
                    tx.success();
                    tx.finish();
                    tx = database.beginTx();
                }
            } // TODO ask charset to user?
            tx.success();
        } finally {
            tx.finish();
        }

        long stopTime = System.currentTimeMillis();
        log("info | Re-indexing elapsed time in seconds: " + (1.0 * (stopTime - startTime) / 1000.0));
        stats.dumpGeomStats();
    }

    private static class GeometryMetaData {
        private Envelope bbox = new Envelope();
        private int vertices = 0;
        private int geometry = -1;
        public GeometryMetaData(int type) {
        	this.geometry = type;
		}
		public int getGeometryType() {
        	return geometry;
        }
		public void expandToIncludePoint(double[] location) {
			bbox.expandToInclude(location[0], location[1]);
			vertices++;
			geometry = -1;
		}
		public void expandToIncludeBBox(Map<String, Object> nodeProps) {
            double[] sbb = (double[])nodeProps.get("bbox");
            bbox.expandToInclude(sbb[0], sbb[2]);
            bbox.expandToInclude(sbb[1], sbb[3]);
            vertices += (Integer)nodeProps.get("vertices");
		}
		public void checkSupportedGeometry(Integer memGType) {
			if ((memGType == null || memGType != GTYPE_LINESTRING) && geometry != GTYPE_POLYGON) {
				geometry = -1;
			}
		}
		public void setPolygon() {
			geometry = GTYPE_POLYGON;
		}
		public boolean isValid() {
			return geometry > 0;
		}
		public int getVertices() {
			return vertices;
		}
		public Envelope getBBox() {
			return bbox;
		}
    }

    private static abstract class OSMWriter<T> {
		protected StatsManager statsManager;

		private OSMWriter(StatsManager statsManager) {
    		this.statsManager = statsManager;
		}

		public static OSMWriter<Long> fromBatchInserter(BatchInserter batchInserter, StatsManager stats) {
			return new OSMBatchWriter(batchInserter, stats);
		}

    	public static OSMWriter<Node> fromGraphDatabase(GraphDatabaseService graphDb, StatsManager stats) {
			return new OSMGraphWriter(graphDb, stats);
		}

	    protected abstract T getOrCreateNode(String name, String type, T parent, RelationshipType relType);

	    protected abstract T getOrCreateOSMDataset(String name);

		protected abstract void setDatasetProperties(Map<String, Object> extractProperties);

		protected abstract void addNodeTags(T currentNode, LinkedHashMap<String, Object> tags, String type);

	    protected abstract void addNodeGeometry(T currentNode, int gtype, Envelope bbox, int vertices);

	    protected abstract T addNode(String name, Map<String, Object> properties, String indexKey);

		protected abstract void createRelationship(T from, T to, RelationshipType relType, LinkedHashMap<String, Object> relProps);

		protected void createRelationship(T from, T to, RelationshipType relType) {
			createRelationship(from, to, relType, null);
		}

		protected HashMap<String, Integer> stats = new HashMap<String, Integer>();
		protected long logTime = 0;
	    protected static int foundNodes=0;
	    protected static int createdNodes=0;

		protected void logNodeAddition(LinkedHashMap<String, Object> tags, String type) {
			Integer count = stats.get(type);
			if (count == null) {
				count = 1;
			} else {
				count++;
			}
			stats.put(type, count);
			long currentTime = System.currentTimeMillis();
			if (currentTime - logTime > 1432) {
				System.out.println(new Date(currentTime) + ": Saving " + type + " " + count + " (created " + createdNodes + ", found "
				        + foundNodes + ")");
				logTime = currentTime;
				//batchIndexService.optimize();
			}
		}

		void describeLoaded() {
			for (String type : new String[] { "node", "way", "relation" }) {
				Integer count = stats.get(type);
				if (count != null) {
					System.out.println("Loaded " + count + " " + type + "s");
				}
			}
		}

		protected abstract long getDatasetId();

		/**
		 * This method should be overridden by implementation that are able to perform database or index optimizations when requested, like the batch inserter.
		 */
		protected void optimize() {
		}

		protected abstract T getSingleNode(String string, Object value);

		protected abstract Map<String, Object> getNodeProperties(T member);

		protected abstract IndexHits<T> getNodes(String string, long nd_ref);

		protected abstract void updateGeometryMetaDataFromMember(T member, GeometryMetaData metaGeom, Map<String, Object> nodeProps);

		protected abstract void shutdownIndex();

		protected abstract T createProxyNode();

    }

    public static final String INDEX_NODES = "nodes";
    private static class OSMGraphWriter extends OSMWriter<Node> {
        private GraphDatabaseService graphDb;
		private Node osm_root;
	    private Node osm_dataset;
        private Index<Node> indexService;

		private OSMGraphWriter(GraphDatabaseService graphDb, StatsManager statsManager) {
			super(statsManager);
    		this.graphDb = graphDb;
    		this.indexService = graphDb.index().forNodes( INDEX_NODES );
    	}

	    private Node findNode(String name, Node parent, RelationshipType relType) {
	        for (Relationship relationship : parent.getRelationships(relType, Direction.OUTGOING)) {
	            Node node = relationship.getEndNode();
	            if (name.equals(node.getProperty("name"))) {
	                return node;
	            }
	        }
	        return null;
	    }

	    @Override
	    protected Node getOrCreateNode(String name, String type, Node parent, RelationshipType relType) {
	        Node node = findNode(name, parent, relType);
	        if (node == null) {
	            node = graphDb.createNode();
	            node.setProperty("name", name);
	            node.setProperty("type", type);
	            parent.createRelationshipTo(node, relType);
	        }
	        return node;
	    }

		@Override
		protected Node getOrCreateOSMDataset(String name) {
	        if (osm_dataset == null) {
	            osm_root = getOrCreateNode("osm_root", "osm", graphDb.getReferenceNode(), OSMRelation.OSM);
	            osm_dataset = getOrCreateNode(name, "osm", osm_root, OSMRelation.OSM);
	        }
	        return osm_dataset;
		}

		@Override
		protected void setDatasetProperties(Map<String, Object> extractProperties) {
			for (String key : extractProperties.keySet()) {
				osm_dataset.setProperty(key, extractProperties.get(key));
			}
		}

		private void addProperties(PropertyContainer node, Map<String, Object> properties) {
			for(String property: properties.keySet()) {
				node.setProperty(property, properties.get(property));
			}
		}

		@Override
		protected void addNodeTags(Node currentNode, LinkedHashMap<String, Object> tags, String type) {
			logNodeAddition(tags, type);
			if (currentNode != null && tags.size() > 0) {
				statsManager.addToTagStats(type, tags.keySet());
				Node node = graphDb.createNode();
				addProperties(node, tags);
				currentNode.createRelationshipTo(node, OSMRelation.TAGS);
				tags.clear();
			}
		}

		@Override
	    protected void addNodeGeometry(Node currentNode, int gtype, Envelope bbox, int vertices) {
	        if (currentNode != null && !bbox.isNull() && vertices > 0) {
	            if (gtype == GTYPE_GEOMETRY)
	                gtype = vertices > 1 ? GTYPE_MULTIPOINT : GTYPE_POINT;
		        Node node = graphDb.createNode();
	            node.setProperty("gtype", gtype);
	            node.setProperty("vertices", vertices);
	            node.setProperty("bbox", new double[] {bbox.getMinX(), bbox.getMaxX(), bbox.getMinY(), bbox.getMaxY()});
	            currentNode.createRelationshipTo(node, OSMRelation.GEOM);
	            statsManager.addGeomStats(gtype);
	        }
	    }

		@Override
		protected Node addNode(String name, Map<String, Object> properties, String indexKey) {
			Node node = graphDb.createNode();
			addProperties(node, properties);
			if (indexKey != null && properties.containsKey(indexKey)) {
				indexService.add(node, indexKey, properties.get(indexKey));
			}
			return node;
		}

		protected Node addNodeWithCheck(String name, Map<String, Object> properties, String indexKey) {
			Node node = null;
			Object indexValue = (indexKey==null) ? null : properties.get(indexKey);
			if (indexValue != null && (createdNodes+foundNodes < 100 || foundNodes > 10)) {
				node = indexService.get(indexKey, properties.get(indexKey)).getSingle();
			}
			if (node == null) {
				node = graphDb.createNode();
				addProperties(node, properties);
				if (indexValue != null) {
					indexService.add(node, indexKey, properties.get(indexKey));
				}
				createdNodes++;
			}else{
				foundNodes++;
			}
			return node;
		}

		@Override
		protected void createRelationship(Node from, Node to, RelationshipType relType, LinkedHashMap<String, Object> relProps) {
			Relationship rel = from.createRelationshipTo(to, relType);
			if(relProps!=null && relProps.size()>0){
				addProperties(rel, relProps);
			}
		}
		
		@Override
		protected long getDatasetId() {
			return osm_dataset.getId();
		}

		@Override
		protected Node getSingleNode(String string, Object value) {
			return indexService.get(string, value).getSingle();
		}

		@Override
		protected Map<String, Object> getNodeProperties(Node node) {
			LinkedHashMap<String,Object> properties = new LinkedHashMap<String,Object>();
			for(String property:node.getPropertyKeys()){
				properties.put(property, node.getProperty(property));
			}
			return properties;
		}

		@Override
		protected IndexHits<Node> getNodes(String string, long nd_ref) {
			return indexService.get(string, nd_ref);
		}

		@Override
		protected void updateGeometryMetaDataFromMember(Node member, GeometryMetaData metaGeom, Map<String, Object> nodeProps) {
			for (Relationship rel : member.getRelationships(OSMRelation.GEOM)) {
				nodeProps = getNodeProperties(rel.getEndNode());
				metaGeom.checkSupportedGeometry((Integer) nodeProps.get("gtype"));
				metaGeom.expandToIncludeBBox(nodeProps);
			}
		}

		@Override
		protected void shutdownIndex() {
			indexService = null;
		}

		@Override
		protected Node createProxyNode() {
			return graphDb.createNode();
		}

    }

    private static class OSMBatchWriter extends OSMWriter<Long> {
    	private BatchInserter batchInserter;
		//private BatchInserterIndexProvider batchIndexService;
        //private BatchInserterIndex batchIndex;
	    private long osm_root;
	    private long osm_dataset;
        private BerkeleyDbBatchInserterIndexProvider bdbBatchIndexService;
        private BerkeleyDbBatchInserterIndex bdbBatchIndex;
        private Database nodeCache;

		private OSMBatchWriter(BatchInserter batchGraphDb, StatsManager statsManager) {
			super(statsManager);
    		this.batchInserter = batchGraphDb;
            Map<String, String> config = new HashMap<String, String>();
            config.put( "type", "exact" );
            //this.batchIndexService = new LuceneBatchInserterIndexProvider(batchGraphDb);
            //this.batchIndex = batchIndexService.nodeIndex( INDEX_NODES, config  );
            this.bdbBatchIndexService = new BerkeleyDbBatchInserterIndexProvider(batchGraphDb);
            this.bdbBatchIndex = (BerkeleyDbBatchInserterIndex) bdbBatchIndexService.nodeIndex( INDEX_NODES, config  );
            
            nodeCache = bdbBatchIndex.createDB( "nodeCache" );
    	}
		
		@Override
	    public Long getOrCreateOSMDataset(String name) {
	        if (osm_dataset <= 0) {
	            osm_root = getOrCreateNode("osm_root", "osm", batchInserter.getReferenceNode(), OSMRelation.OSM);
	            osm_dataset = getOrCreateNode(name, "osm", osm_root, OSMRelation.OSM);
	        }
	        return osm_dataset;
	    }

	    private long findNode(BatchInserter batchInserter, String name, long parent, RelationshipType relType) {
	        for (SimpleRelationship relationship : batchInserter.getRelationships(parent)) {
	            if (relationship.getType().name().equals(relType.name())) {
	                long node = relationship.getEndNode();
	                Object nodeName = batchInserter.getNodeProperties(node).get("name");
	                if (nodeName != null && name.equals(nodeName.toString())) {
	                    return node;
	                }
	            }
	        }
	        return -1;
	    }

	    @Override
	    protected Long getOrCreateNode(String name, String type, Long parent, RelationshipType relType) {
	        long node = findNode(batchInserter, name, parent, relType);
	        if (node < 0) {
	            HashMap<String, Object> properties = new HashMap<String, Object>();
	            properties.put("name", name);
	            properties.put("type", type);
	            node = batchInserter.createNode(properties);
	            
	            nodeCache.put( null, new DatabaseEntry(serializeToBytes(node)), new DatabaseEntry(serializeToBytes(properties)) );
	            batchInserter.createRelationship(parent, node, relType, null);
	        }
	        return node;
	    }
        private byte[] serializeToBytes( Object data )
        {
            ByteArrayOutputStream baus = new ByteArrayOutputStream();
            try
            {
                ObjectOutputStream dos = new ObjectOutputStream( baus );
                dos.writeObject(  data );
                dos.flush();
                dos.close();
            }
            catch ( IOException e )
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return baus.toByteArray();
        }
        
        private Object getObjectFromBytes( byte[] data )
        {
            ByteArrayInputStream baus = new ByteArrayInputStream(data);
            Object result = null;
            try
            {
                ObjectInputStream dos = new ObjectInputStream( baus );
                result = dos.readObject( );
                dos.close();
            }
            catch ( Exception e )
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return result;
        }

        public String toString() {
//			return "BatchInserter["+batchInserter.toString()+"]:IndexService["+batchIndexService.toString()+", "+bdbBatchIndexService.toString()+"]";
            return "BatchInserter["+batchInserter.toString()+"]:IndexService["+bdbBatchIndexService.toString()+"]";
		}

		@Override
		protected void setDatasetProperties(Map<String, Object> extraProperties) {
            LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
            properties.putAll(batchInserter.getNodeProperties(osm_dataset));
            properties.putAll(extraProperties);
            batchInserter.setNodeProperties(osm_dataset, properties);
		}

		@Override
		protected void addNodeTags(Long currentNode, LinkedHashMap<String, Object> tags, String type) {
			logNodeAddition(tags, type);
			if (currentNode > 0 && tags.size() > 0) {
				statsManager.addToTagStats(type, tags.keySet());
				long id = batchInserter.createNode(tags);
                nodeCache.put( null, new DatabaseEntry(serializeToBytes(id)), new DatabaseEntry(serializeToBytes(tags)) );

				batchInserter.createRelationship(currentNode, id, OSMRelation.TAGS, new HashMap());
				tags.clear();
			}
		}

		@Override
	    protected void addNodeGeometry(Long currentNode, int gtype, Envelope bbox, int vertices) {
	        if (currentNode > 0 && !bbox.isNull() && vertices > 0) {
	            LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
	            if (gtype == GTYPE_GEOMETRY)
	                gtype = vertices > 1 ? GTYPE_MULTIPOINT : GTYPE_POINT;
	            properties.put("gtype", gtype);
	            properties.put("vertices", vertices);
	            properties.put("bbox", new double[] {bbox.getMinX(), bbox.getMaxX(), bbox.getMinY(), bbox.getMaxY()});
	            long id = batchInserter.createNode(properties);
                nodeCache.put( null, new DatabaseEntry(serializeToBytes(id)), new DatabaseEntry(serializeToBytes(properties)) );

	            batchInserter.createRelationship(currentNode, id, OSMRelation.GEOM, null);
	            properties.clear();
	            statsManager.addGeomStats(gtype);
	        }
	    }

		@Override
		protected Long addNode(String name, Map<String, Object> properties, String indexKey) {
			long id = batchInserter.createNode(properties);
			nodeCache.put( null, new DatabaseEntry(serializeToBytes(id)), new DatabaseEntry(serializeToBytes(properties)) );

			if (indexKey != null && properties.containsKey(indexKey)) {
			    
				Map<String, Object> props = new HashMap<String, Object>();
				props.put( indexKey, properties.get(indexKey));
                //batchIndex.add(id, props );
				bdbBatchIndex.add( id, props );
			}
			return id;
		}

		protected Long addNodeWithCheck(String name, Map<String, Object> properties, String indexKey) {
			// TODO: This code allows for importing into existing data, but
			// slows the import down by almost three times. The problem is that
			// the batchIndexService cannot switch efficiently between read and
			// write mode. Rather we should use pure GraphDatabaseService API
			// for update mode.
			long id = -1;
			Object indexValue = (indexKey==null) ? null : properties.get(indexKey);
			if (indexValue != null && (createdNodes+foundNodes < 100 || foundNodes > 10)) {
				id = bdbBatchIndex.get(indexKey, properties.get(indexKey)).getSingle();
			}
			if (id < 0) {
				id = batchInserter.createNode(properties);
	            nodeCache.put( null, new DatabaseEntry(serializeToBytes(id)), new DatabaseEntry(serializeToBytes(properties)) );

				if (indexValue != null) {
					Map<String, Object> props = new HashMap<String, Object>();
					props.put( indexKey, properties.get(indexKey) );
					bdbBatchIndex.add(id, props );
				}
				createdNodes++;
			}else{
				foundNodes++;
			}
			return id;
		}

		@Override
		protected void createRelationship(Long from, Long to, RelationshipType relType, LinkedHashMap<String, Object> relProps) {
			batchInserter.createRelationship(from, to, relType, relProps);
		}
		
		protected void optimize() {
			//TODO: optimize
		    //batchIndexService.optimize();
		}

		@Override
		protected long getDatasetId() {
			return osm_dataset;
		}

		@Override
		protected Long getSingleNode(String string, Object value) {
			return bdbBatchIndex.get(string, value).getSingle();
		}

		//TODO: this is slow. We should think of replacing this with some exact lookup
		@Override
		protected Map<String, Object> getNodeProperties(Long member) {
            DatabaseEntry result = new DatabaseEntry();
            OperationStatus status = nodeCache.get(null, new DatabaseEntry(serializeToBytes( member )), result, LockMode.READ_UNCOMMITTED );
            return (Map<String, Object>) getObjectFromBytes(result.getData());
//			return batchInserter.getNodeProperties(member);
		}

		@Override
		protected IndexHits<Long> getNodes(String string, long nd_ref) {
			return bdbBatchIndex.get(string, nd_ref);
		}

		@Override
		protected void updateGeometryMetaDataFromMember(Long member, GeometryMetaData metaGeom, Map<String, Object> nodeProps) {
			for (SimpleRelationship rel : batchInserter.getRelationships(member)) {
				if (rel.getType().equals(OSMRelation.GEOM)) {
					nodeProps = getNodeProperties(rel.getEndNode());
					metaGeom.checkSupportedGeometry((Integer) nodeProps.get("gtype"));
					metaGeom.expandToIncludeBBox(nodeProps);
				}
			}
		}

		@Override
		protected void shutdownIndex() {
			//batchIndexService.shutdown();
			//batchIndexService = null;
			bdbBatchIndexService.shutdown();
			bdbBatchIndex = null;
		}

		@Override
		protected Long createProxyNode() {
			long id = batchInserter.createNode(null);
            nodeCache.put( null, new DatabaseEntry(serializeToBytes(id)), new DatabaseEntry(serializeToBytes(null)) );

            return id;
		}

    }

    @SuppressWarnings("restriction")
	public void importFile(BatchInserter batchInserter, String dataset) throws IOException, XMLStreamException {
    	importFile(batchInserter, dataset, false);
    }

    @SuppressWarnings("restriction")
    public void importFile(BatchInserter batchInserter, String dataset, boolean allPoints) throws IOException, XMLStreamException {
    	OSMWriter<Long> osmWriter = OSMWriter.fromBatchInserter(batchInserter,stats);
		System.out.println("Importing with osm-writer: " + osmWriter);
        osm_dataset = osmWriter.getOrCreateOSMDataset(layerName);

        long startTime = System.currentTimeMillis();
        long[] times = new long[]{0L,0L,0L,0L};
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
        javax.xml.stream.XMLStreamReader parser = factory.createXMLStreamReader(new FileReader(dataset));
        int countXMLTags = 0;
        setLogContext(dataset);
        boolean startedWays = false;
        boolean startedRelations = false;
        try {
            ArrayList<String> currentXMLTags = new ArrayList<String>();
            int depth = 0;
            long currentNode = -1;
            long prev_way = -1;
            long prev_relation = -1;
            Map<String, Object> wayProperties = null;
            ArrayList<Long> wayNodes = new ArrayList<Long>();
            Map<String, Object> relationProperties = null;
            ArrayList<Map<String, Object>> relationMembers = new ArrayList<Map<String, Object>>();
            LinkedHashMap<String, Object> currentNodeTags = new LinkedHashMap<String, Object>();
            while (true) {
                incrLogContext();
                int event = parser.next();
                if (event == javax.xml.stream.XMLStreamConstants.END_DOCUMENT) {
                    break;
                }
                switch (event) {
                case javax.xml.stream.XMLStreamConstants.START_ELEMENT:
                    currentXMLTags.add(depth, parser.getLocalName());
                    String tagPath = currentXMLTags.toString();
                    if (tagPath.equals("[osm]")) {
                    	osmWriter.setDatasetProperties(extractProperties(parser));
                    } else if (tagPath.equals("[osm, bounds]")) {
                        long bbox = (Long)osmWriter.addNode("bbox", extractProperties("bbox", parser), null);
                        osmWriter.createRelationship(osm_dataset, bbox, OSMRelation.BBOX);
                    } else if (tagPath.equals("[osm, node]")) {
                        currentNode = (Long)osmWriter.addNode("node", extractProperties("node", parser), "node_osm_id");
                        Map<String, Object> nodeProperties = osmWriter.getNodeProperties(currentNode);
                        String node_osm_id = (String)nodeProperties.get("node_osm_id");
                        if(node_osm_id.equals("8090260") || node_osm_id.equals("273534207")) {
                        	System.out.println("Debug node: "+node_osm_id);
                        }
                    } else if (tagPath.equals("[osm, way]")) {
                        if (!startedWays) {
                            startedWays = true;
                            times[0] = System.currentTimeMillis();
                            osmWriter.optimize();
                            times[1] = System.currentTimeMillis();
                        }
                        wayProperties = extractProperties("way", parser);
                        wayNodes.clear();
                    } else if (tagPath.equals("[osm, way, nd]")) {
                        Map<String, Object> properties = extractProperties(parser);
                        wayNodes.add(Long.parseLong(properties.get("ref").toString()));
                    } else if (tagPath.endsWith("tag]")) {
                        Map<String, Object> properties = extractProperties(parser);
                        currentNodeTags.put(properties.get("k").toString(), properties.get("v").toString());
                    } else if (tagPath.equals("[osm, relation]")) {
                        if (!startedRelations) {
                            startedRelations = true;
                            times[2] = System.currentTimeMillis();
                            osmWriter.optimize();
                            times[3] = System.currentTimeMillis();
                        }
                        relationProperties = extractProperties("relation", parser);
                        relationMembers.clear();
                    } else if (tagPath.equals("[osm, relation, member]")) {
                        relationMembers.add(extractProperties(parser));
                    }
                    if (startedRelations) {
                        if (countXMLTags < 10) {
                            log("Starting tag at depth " + depth + ": " + currentXMLTags.get(depth) + " - " + currentXMLTags.toString());
                            for (int i = 0; i < parser.getAttributeCount(); i++) {
                                log("\t" + currentXMLTags.toString() + ": " + parser.getAttributeLocalName(i) + "["
                                        + parser.getAttributeNamespace(i) + "," + parser.getAttributePrefix(i) + ","
                                        + parser.getAttributeType(i) + "," + "] = " + parser.getAttributeValue(i));
                            }
                        }
                        countXMLTags++;
                    }
                    depth++;
                    break;
                case javax.xml.stream.XMLStreamConstants.END_ELEMENT:
                    if (currentXMLTags.toString().equals("[osm, node]")) {
                    	currentNodeTags.remove("created_by");  // redundant information
                        // Nodes with tags get added to the index as point geometries
                        if(allPoints || currentNodeTags.size()>0) {
                            Map<String, Object> nodeProps = osmWriter.getNodeProperties(currentNode);
                            Envelope bbox = new Envelope();
                            double[] location = new double[] {(Double)nodeProps.get("lon"), (Double)nodeProps.get("lat")};
                            bbox.expandToInclude(location[0], location[1]);
                        	osmWriter.addNodeGeometry(currentNode, GTYPE_POINT, bbox, 1);
                        }
                        osmWriter.addNodeTags(currentNode, currentNodeTags, "node");
                    } else if (currentXMLTags.toString().equals("[osm, way]")) {
                        RoadDirection direction = isOneway(currentNodeTags);
                        String name = (String)currentNodeTags.get("name");
                        int geometry = GTYPE_LINESTRING;
                        boolean isRoad = currentNodeTags.containsKey("highway");
                        if (isRoad) {
                            wayProperties.put("oneway", direction.toString());
                            wayProperties.put("highway", currentNodeTags.get("highway"));
                        }
                        if (name != null) {
                            // Copy name tag to way because this seems like a valuable location for
                            // such a property
                            wayProperties.put("name", name);
                        }
                        String way_osm_id = (String)wayProperties.get("way_osm_id");
                        if(way_osm_id.equals("28338132")) {
                        	System.out.println("Debug way: "+way_osm_id);
                        }
                        long way = (Long)osmWriter.addNode("way", wayProperties, "way_osm_id");
                        if (prev_way < 0) {
                            osmWriter.createRelationship(osm_dataset, way, OSMRelation.WAYS);
                        } else {
                            osmWriter.createRelationship(prev_way, way, OSMRelation.NEXT);
                        }
                        prev_way = way;
                        osmWriter.addNodeTags(way, currentNodeTags, "way") ;
                        Envelope bbox = new Envelope();
                        long firstNode = -1;
                        long prevNode = -1;
                        long prevProxy = -1;
                        Map<String, Object> prevProps = null;
                        LinkedHashMap<String, Object> relProps = new LinkedHashMap<String, Object>();
                        HashMap<String, Object> directionProps = new HashMap<String, Object>();
                        directionProps.put("oneway", true);
                        for (long nd_ref : wayNodes) {
                            //long pointNode = batchIndexService.getSingleNode("node_osm_id", nd_ref);
							IndexHits<Long> hits = osmWriter.getNodes("node_osm_id", nd_ref);
							if (hits.size() == 0) {
                                /*
                                 * This can happen if we import not whole planet, so some referenced
                                 * nodes will be unavailable
                                 */
                                missingNode(nd_ref);
								continue;
							} else if (hits.size() > 1) {
								System.err.println("More than one way node with node_osm_id: " + nd_ref);
								for (long hit : hits) {
									Map<String, Object> hitProps = osmWriter.getNodeProperties(hit);
									System.err.println("\tid = " + hit + ", name = " + hitProps.get("name"));
								}
							}
							long pointNode = hits.next();
                            long proxyNode = osmWriter.createProxyNode();
							if (firstNode == -1) {
								firstNode = pointNode;
							}
                            if (prevNode == pointNode) {
                                continue;
                            }
                            osmWriter.createRelationship(proxyNode, pointNode, OSMRelation.NODE, null);
                            Map<String, Object> nodeProps = osmWriter.getNodeProperties(pointNode);
                            double[] location = new double[] {(Double)nodeProps.get("lon"), (Double)nodeProps.get("lat")};
                            bbox.expandToInclude(location[0], location[1]);
                            if (prevProxy < 0) {
                                osmWriter.createRelationship(way, proxyNode, OSMRelation.FIRST_NODE);
                            } else {
                                relProps.clear();
                                double[] prevLoc = new double[] {(Double)prevProps.get("lon"), (Double)prevProps.get("lat")};

                                double length = distance(prevLoc[0], prevLoc[1], location[0], location[1]);
                                relProps.put("length", length);

                                // We default to bi-directional (and don't store direction in the
                                // way node), but if it is one-way we mark it as such, and define
                                // the direction using the relationship direction
                                if (direction == RoadDirection.BACKWARD) {
                                    osmWriter.createRelationship(proxyNode, prevProxy, OSMRelation.NEXT, relProps);
                                } else {
                                    osmWriter.createRelationship(prevProxy, proxyNode, OSMRelation.NEXT, relProps);
                                }
                            }
                            prevNode = pointNode;
                            prevProxy = proxyNode;
                            prevProps = nodeProps;
                        }
//                        if (prevNode > 0) {
//                            batchGraphDb.createRelationship(way, prevNode, OSMRelation.LAST_NODE, null);
//                        }
						if (firstNode > 0 && prevNode == firstNode) {
							geometry = GTYPE_POLYGON;
						}
						if (wayNodes.size() < 2) {
							geometry = GTYPE_POINT;
						}
                        osmWriter.addNodeGeometry(way, geometry, bbox, wayNodes.size());
                    } else if (currentXMLTags.toString().equals("[osm, relation]")) {
                        String name = (String)currentNodeTags.get("name");
                        if (name != null) {
                            // Copy name tag to way because this seems like a valuable location for
                            // such a property
                            relationProperties.put("name", name);
                        }
                        long relation = osmWriter.addNode("relation", relationProperties, "relation_osm_id");
                        if (prev_relation < 0) {
                            osmWriter.createRelationship(osm_dataset, relation, OSMRelation.RELATIONS);
                        } else {
                            osmWriter.createRelationship(prev_relation, relation, OSMRelation.NEXT);
                        }
                        prev_relation = relation;
                        osmWriter.addNodeTags(relation, currentNodeTags, "relation");
                        // We will test for cases that invalidate multilinestring further down
                        GeometryMetaData metaGeom = new GeometryMetaData(GTYPE_MULTILINESTRING);
                        long prevMember = -1;
                        LinkedHashMap<String, Object> relProps = new LinkedHashMap<String, Object>();
                        for (Map<String, Object> memberProps : relationMembers) {
                            String memberType = (String)memberProps.get("type");
                            long member_ref = Long.parseLong(memberProps.get("ref").toString());
                            if (memberType != null) {
                                Long member = osmWriter.getSingleNode(memberType + "_osm_id", member_ref);
                                if (null == member || prevMember == member) {
                                    /*
                                     * This can happen if we import not whole planet, so some
                                     * referenced nodes will be unavailable
                                     */
                                    missingMember(memberProps.toString());
                                    continue;
                                }
                                if (member == relation) {
                                    error("Cannot add relation to same member: relation[" + currentNodeTags + "] - member["
                                            + memberProps + "]");
                                    continue;
                                }
                                Map<String, Object> nodeProps = osmWriter.getNodeProperties(member);
                                if (memberType.equals("node")) {
                                    double[] location = new double[] {(Double)nodeProps.get("lon"), (Double)nodeProps.get("lat")};
                                    metaGeom.expandToIncludePoint(location);
                                } else {
									osmWriter.updateGeometryMetaDataFromMember(member, metaGeom, nodeProps);
                                }
                                relProps.clear();
								String role = (String) memberProps.get("role");
								if (role != null && role.length() > 0) {
									relProps.put("role", role);
									if(role.equals("outer")){
										metaGeom.setPolygon();
									}
								}
                                osmWriter.createRelationship(relation, member, OSMRelation.MEMBER, relProps);
                                // members can belong to multiple relations, in multiple orders, so NEXT will clash (also with NEXT between ways in original way load)
//                                if (prevMember < 0) {
//                                    batchGraphDb.createRelationship(relation, member, OSMRelation.MEMBERS, null);
//                                } else {
//                                    batchGraphDb.createRelationship(prevMember, member, OSMRelation.NEXT, null);
//                                }
                                prevMember = member;
                            } else {
                                System.err.println("Cannot process invalid relation member: " + memberProps.toString());
                            }
                        }
						if (metaGeom.isValid()) {
							osmWriter.addNodeGeometry(relation, metaGeom.getGeometryType(), metaGeom.getBBox(), metaGeom.getVertices());
						}
                    }
                    depth--;
                    currentXMLTags.remove(depth);
                    // log("Ending tag at depth "+depth+": "+currentTags.get(depth));
                    break;
                default:
                    break;
                }
            }
        } finally {
            parser.close();
            osmWriter.shutdownIndex();
            this.osm_dataset = osmWriter.getDatasetId();
        }
        describeTimes(startTime,times);
        describeMissing();
        osmWriter.describeLoaded();

        long stopTime = System.currentTimeMillis();
        log("info | Elapsed time in seconds: " + (1.0 * (stopTime - startTime) / 1000.0));
        stats.dumpGeomStats();
        stats.printTagStats();
    }

	private void describeTimes(long startTime, long[] times) {
		long endTime = System.currentTimeMillis();
		log("Completed load in " + (1.0 * (endTime - startTime) / 1000.0) + "s");
		log("\tImported nodes:  " + (1.0 * (times[0] - startTime) / 1000.0) + "s");
		log("\tOptimized index: " + (1.0 * (times[1] - times[0]) / 1000.0) + "s");
		log("\tImported ways:   " + (1.0 * (times[2] - times[1]) / 1000.0) + "s");
		log("\tOptimized index: " + (1.0 * (times[3] - times[2]) / 1000.0) + "s");
		log("\tImported rels:   " + (1.0 * (endTime - times[3]) / 1000.0) + "s");
	}

    private int missingNodeCount = 0;

    private void missingNode(long ndRef) {
        if (missingNodeCount++ < 10) {
            error("Cannot find node for osm-id " + ndRef);
        }
    }

    private void describeMissing() {
        if (missingNodeCount > 0) {
            error("When processing the ways, there were " + missingNodeCount + " missing nodes");
        }
        if (missingMemberCount > 0) {
            error("When processing the relations, there were " + missingMemberCount + " missing members");
        }
    }

    private int missingMemberCount = 0;

    private void missingMember(String description) {
        if (missingMemberCount++ < 10) {
            error("Cannot find member: " + description);
        }
    }

    @SuppressWarnings("restriction")
	private Map<String, Object> extractProperties(XMLStreamReader parser) {
        return extractProperties(null, parser);
    }

    @SuppressWarnings("restriction")
	private Map<String, Object> extractProperties(String name, XMLStreamReader parser) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String prop = parser.getAttributeLocalName(i);
            if (name != null && prop.equals("id")) {
                prop = name + "_osm_id";
                name = null;
            }
            if (prop.equals("lat") || prop.equals("lon")) {
                properties.put(prop, Double.parseDouble(parser.getAttributeValue(i)));
            } else {
                properties.put(prop, parser.getAttributeValue(i));
            }
        }
        if (name != null) {
            properties.put("name", name);
        }
        return properties;
    }

    /**
     * Detects if road has the only direction
     * 
     * @param wayProperties
     * @return RoadDirection
     */
    public static RoadDirection isOneway(Map<String, Object> wayProperties) {
        String oneway = (String)wayProperties.get("oneway");
        if (null != oneway) {
            if ("-1".equals(oneway))
                return RoadDirection.BACKWARD;
            if ("1".equals(oneway) || "yes".equalsIgnoreCase(oneway) || "true".equalsIgnoreCase(oneway))
                return RoadDirection.FORWARD;
        }
        return RoadDirection.BOTH;
    }

    /**
     * Calculate correct distance between 2 points on Earth.
     * 
     * @param latA
     * @param lonA
     * @param latB
     * @param lonB
     * @return distance in meters
     */
    public static double distance(double lonA, double latA, double lonB, double latB) {
        return WGS84.orthodromicDistance(lonA, latA, lonB, latB);
    }

    private void log(PrintStream out, String message, Exception e) {
        if (logContext != null) {
            message = logContext + "[" + contextLine + "]: " + message;
        }
        out.println(message);
        if (e != null) {
            e.printStackTrace(out);
        }
    }

    private void log(String message) {
        log(System.out, message, null);
    }

    private void error(String message) {
        log(System.err, message, null);
    }

    private void error(String message, Exception e) {
        log(System.err, message, e);
    }

    private String logContext = null;
    private int contextLine = 0;

    private void setLogContext(String context) {
        logContext = context;
        contextLine = 0;
    }

    private void incrLogContext() {
        contextLine++;
    }

	/**
	 * This method allows for a console, command-line application for loading
	 * one or more *.osm files into a new database.
	 * 
	 * @param args
	 *            , the database directory followed by one or more osm files
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("Usage: osmimporter databasedir osmfile <..osmfiles..>");
		} else {
			OSMImportManager importer = new OSMImportManager(args[0]);
			for (int i = 1; i < args.length; i++) {
				try {
					importer.loadTestOsmData(args[i], 1000);
				} catch (Exception e) {
					System.err.println("Error importing OSM file '" + args[i] + "': " + e);
					e.printStackTrace();
				} finally {
					importer.shutdown();
				}
			}
		}
	}

	private static class OSMImportManager {
		private GraphDatabaseService graphDb;
		private BatchInserter batchInserter;
		private File dbPath;

		public OSMImportManager(String path) {
			setDbPath(path);
		}

		public void setDbPath(String path) {
			dbPath = new File(path);
			if (dbPath.exists()) {
				if (!dbPath.isDirectory()) {
					throw new RuntimeException("Database path is an existing file: " + dbPath.getAbsolutePath());
				}
			} else {
				dbPath.mkdirs();
			}
		}

		private void loadTestOsmData(String layerName, int commitInterval) throws Exception {
			String osmPath = layerName;
			System.out.println("\n=== Loading layer " + layerName + " from " + osmPath + " ===");
			long start = System.currentTimeMillis();
			switchToBatchInserter();
			OSMImporter importer = new OSMImporter(layerName);
			importer.importFile(batchInserter, osmPath);
			switchToEmbeddedGraphDatabase();
			importer.reIndex(graphDb, commitInterval);
			shutdown();
			System.out.println("=== Completed loading " + layerName + " in " + (System.currentTimeMillis() - start) / 1000.0 + " seconds ===");
		}

		private void switchToEmbeddedGraphDatabase() {
			shutdown();
			graphDb = new EmbeddedGraphDatabase(dbPath.getAbsolutePath());
		}

		private void switchToBatchInserter() {
			shutdown();
			batchInserter = new BatchInserterImpl(dbPath.getAbsolutePath());
			graphDb = batchInserter.getGraphDbService();
		}

		protected void shutdown() {
			if (graphDb != null) {
			    batchInserter.shutdown();
				graphDb.shutdown();
				graphDb = null;
				batchInserter = null;
			}
		}
	}
}
