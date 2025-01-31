/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License,
 * attached with Common Clause Condition 1.0, found in the LICENSES directory.
 */

package com.vesoft.nebula.encoder;

import com.vesoft.nebula.HostAddr;
import com.vesoft.nebula.client.meta.MetaCache;
import com.vesoft.nebula.meta.ColumnDef;
import com.vesoft.nebula.meta.ColumnTypeDef;
import com.vesoft.nebula.meta.EdgeItem;
import com.vesoft.nebula.meta.PropertyType;
import com.vesoft.nebula.meta.Schema;
import com.vesoft.nebula.meta.SpaceItem;
import com.vesoft.nebula.meta.TagItem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.codec.digest.MurmurHash2;

/**
 *  NebulaCodecImpl is an encoder to generate the given data.
 *  If the schema with default value, and the filed without given data, it will throw error.
 *  TODO: Support default value
 */
public class NebulaCodecImpl implements NebulaCodec {
    private static final int PARTITION_ID_SIZE = 4;
    private static final int TAG_ID_SIZE = 4;
    private static final int EDGE_TYPE_SIZE = 4;
    private static final int EDGE_RANKING_SIZE = 8;
    private static final int EDGE_VER_PLACE_HOLDER_SIZE = 1;
    private static final int VERTEX_SIZE = PARTITION_ID_SIZE + TAG_ID_SIZE;
    private static final int EDGE_SIZE = PARTITION_ID_SIZE + EDGE_TYPE_SIZE
        + EDGE_RANKING_SIZE + EDGE_VER_PLACE_HOLDER_SIZE;

    private static final int VERTEX_KEY_TYPE = 0x00000001;
    private static final int EDGE_KEY_TYPE = 0x00000002;
    private static final int SEEK = 0xc70f6907;
    private final MetaCache metaCache;
    private final ByteOrder byteOrder;

    public NebulaCodecImpl(MetaCache metaCache) {
        this.byteOrder = ByteOrder.nativeOrder();
        this.metaCache = metaCache;
    }

    private int getSpaceVidLen(String spaceName) throws RuntimeException {
        SpaceItem spaceItem = metaCache.getSpace(spaceName);
        if (spaceItem == null) {
            throw new RuntimeException("SpaceName: " + spaceName + "is not existed");
        }
        if (spaceItem.properties.vid_type.type != PropertyType.FIXED_STRING) {
            throw new RuntimeException("Only supported fixed string vid type.");
        }
        return spaceItem.properties.vid_type.type_length;
    }

    private int getPartSize(String spaceName) throws RuntimeException {
        Map<Integer, List<HostAddr>> partsAlloc = metaCache.getPartsAlloc(spaceName);
        if (partsAlloc == null) {
            throw new RuntimeException("SpaceName: " + spaceName + " is not existed");
        }
        return partsAlloc.size();
    }

    @Override
    public byte[] vertexKey(String spaceName, String vertexId, String tagName)
        throws RuntimeException {
        int vidLen = getSpaceVidLen(spaceName);
        int partitionId = getPartId(spaceName, vertexId);
        TagItem tagItem = metaCache.getTag(spaceName, tagName);
        return genVertexKey(vidLen, partitionId, vertexId.getBytes(), tagItem.tag_id);
    }

    /**
     * @param spaceName the space name
     * @param srcId the src id
     * @param edgeName the edge name
     * @param edgeRank the ranking
     * @param dstId the dst id
     * @return
     */
    @Override
    public byte[] edgeKey(String spaceName,
                          String srcId,
                          String edgeName,
                          long edgeRank,
                          String dstId)
        throws RuntimeException {
        int vidLen = getSpaceVidLen(spaceName);
        int partitionId = getPartId(spaceName, srcId);
        EdgeItem edgeItem = metaCache.getEdge(spaceName, edgeName);
        return genEdgeKeyByDefaultVer(vidLen,partitionId, srcId.getBytes(),
            edgeItem.edge_type, edgeRank, dstId.getBytes());
    }

    /**
     * @param vidLen the vidLen from the space description
     * @param partitionId the partitionId
     * @param vertexId the vertex id
     * @param tagId the tag id
     * @return
     */
    public byte[] genVertexKey(int vidLen,
                               int partitionId,
                               byte[] vertexId,
                               int tagId) {
        if (vertexId.length > vidLen) {
            throw new RuntimeException(
                "The length of vid size is out of the range, expected vidLen less then " + vidLen);
        }
        ByteBuffer buffer = ByteBuffer.allocate(VERTEX_SIZE + vidLen);
        buffer.order(this.byteOrder);
        partitionId = (partitionId << 8) | VERTEX_KEY_TYPE;
        buffer.putInt(partitionId)
            .put(vertexId);
        if (vertexId.length < vidLen) {
            ByteBuffer complementVid = ByteBuffer.allocate(vidLen - vertexId.length);
            Arrays.fill(complementVid.array(), (byte) '\0');
            buffer.put(complementVid);
        }
        buffer.putInt(tagId);
        return buffer.array();
    }

    /**
     * @param vidLen the vidLen from the space description
     * @param partitionId the partitionId
     * @param srcId the src id
     * @param edgeType the edge type
     * @param edgeRank the ranking
     * @param dstId the dstId
     * @return byte[]
     */
    public byte[] genEdgeKeyByDefaultVer(int vidLen,
                                         int partitionId,
                                         byte[] srcId,
                                         int edgeType,
                                         long edgeRank,
                                         byte[] dstId) {
        return genEdgeKey(vidLen, partitionId, srcId, edgeType, edgeRank, dstId, (byte)1);
    }

    /**
     * @param vidLen the vidLen from the space description
     * @param partitionId the partitionId
     * @param srcId the src id
     * @param edgeType the edge type
     * @param edgeRank the ranking
     * @param dstId the dstId
     * @param edgeVerHolder the edgeVerHolder
     * @return byte[]
     */
    public byte[] genEdgeKey(int vidLen,
                             int partitionId,
                             byte[] srcId,
                             int edgeType,
                             long edgeRank,
                             byte[] dstId,
                             byte edgeVerHolder) {
        if (srcId.length > vidLen || dstId.length > vidLen) {
            throw new RuntimeException(
                "The length of vid size is out of the range, expected vidLen less then " + vidLen);
        }
        ByteBuffer buffer = ByteBuffer.allocate(EDGE_SIZE + (vidLen << 1));
        buffer.order(this.byteOrder);
        partitionId = (partitionId << 8) | EDGE_KEY_TYPE;
        buffer.putInt(partitionId);
        buffer.put(srcId);
        if (srcId.length < vidLen) {
            ByteBuffer complementVid = ByteBuffer.allocate(vidLen - srcId.length);
            Arrays.fill(complementVid.array(), (byte) '\0');
            buffer.put(complementVid);
        }
        buffer.putInt(edgeType);
        buffer.put(encodeRank(edgeRank));
        buffer.put(dstId);
        if (dstId.length < vidLen) {
            ByteBuffer complementVid = ByteBuffer.allocate(vidLen - dstId.length);
            Arrays.fill(complementVid.array(), (byte) '\0');
            buffer.put(complementVid);
        }
        buffer.put(edgeVerHolder);
        return buffer.array();
    }

    public SchemaProviderImpl genSchemaProvider(long ver, Schema schema) {
        SchemaProviderImpl schemaProvider = new SchemaProviderImpl(ver);
        for (ColumnDef col : schema.getColumns()) {
            ColumnTypeDef type = col.getType();
            boolean nullable = col.isSetNullable();
            boolean hasDefault = col.isSetDefault_value();
            int len = type.isSetType_length() ? type.getType_length() : 0;
            schemaProvider.addField(new String(col.getName()),
                                    type.type,
                                    len,
                                    nullable,
                                    hasDefault ? col.getDefault_value() : null);
        }
        return schemaProvider;
    }

    /**
     * @param spaceName the space name
     * @param tagName the tag name
     * @param names the property names
     * @param values the property values
     * @return the encode byte[]
     * @throws RuntimeException expection
     */
    @Override
    public byte[] encodeTag(String spaceName,
                            String tagName,
                            List<String> names,
                            List<Object> values) throws RuntimeException  {
        TagItem tag = metaCache.getTag(spaceName, tagName);
        if (tag == null) {
            throw new RuntimeException(
                String.format("TagItem is null when getting tagName `%s'", tagName));
        }
        Schema schema = tag.getSchema();
        return encode(schema, tag.getVersion(), names, values);
    }

    /**
     * @param spaceName the space name
     * @param edgeName the edge name
     * @param names the property names
     * @param values the property values
     * @return the encode byte[]
     * @throws RuntimeException expection
     */
    @Override
    public byte[] encodeEdge(String spaceName,
                             String edgeName,
                             List<String> names,
                             List<Object> values) throws RuntimeException  {
        EdgeItem edge = metaCache.getEdge(spaceName, edgeName);
        if (edge == null) {
            throw new RuntimeException(
                String.format("EdgeItem is null when getting edgeName `%s'", edgeName));
        }
        Schema schema = edge.getSchema();
        return encode(schema, edge.getVersion(), names, values);
    }

    private byte[] encodeRank(long rank) {
        long newRank = rank ^ (1L << 63);
        ByteBuffer rankBuf = ByteBuffer.allocate(Long.BYTES);
        rankBuf.order(ByteOrder.BIG_ENDIAN);
        rankBuf.putLong(newRank);
        return rankBuf.array();
    }

    private int getPartId(String spaceName, String vertexId) {
        long hash = MurmurHash2.hash64(vertexId.getBytes(), vertexId.length(), SEEK);
        long hashValue = Long.parseUnsignedLong(Long.toUnsignedString(hash));
        return (int) (Math.floorMod(hashValue, getPartSize(spaceName)) + 1);
    }

    /**
     * @param schema the schema
     * @param ver the version of tag or edge
     * @param names the property names
     * @param values the property values
     * @return the encode byte[]
     * @throws RuntimeException expection
     */
    private byte[] encode(Schema schema,
                          long ver,
                          List<String> names,
                          List<Object> values)
        throws RuntimeException {
        if (names.size() != values.size()) {
            throw new RuntimeException(
                String.format("The names' size no equal with values' size, [%d] != [%d]",
                    names.size(), values.size()));
        }
        RowWriterImpl writer = new RowWriterImpl(genSchemaProvider(ver, schema), this.byteOrder);
        for (int i = 0; i < names.size(); i++) {
            writer.setValue(names.get(i), values.get(i));
        }
        writer.finish();
        return writer.encodeStr();
    }
}
