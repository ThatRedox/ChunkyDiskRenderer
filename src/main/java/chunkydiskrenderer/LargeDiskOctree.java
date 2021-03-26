package chunkydiskrenderer;

import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import it.unimi.dsi.fastutil.io.FastBufferedInputStream;
import it.unimi.dsi.fastutil.io.FastBufferedOutputStream;
import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.log.Log;
import se.llbit.math.Octree;
import se.llbit.math.Vector3;

import java.io.*;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * This is an octree that wraps many smaller octrees committed to disk.
 */
public class LargeDiskOctree implements Octree.OctreeImplementation {
    private final int INTERN_OCTREE_DEPTH = 10;
    private static final int DEFAULT_INITIAL_SIZE = 64;
    private static final double ARRAY_RESIZE_MULTIPLIER = 1.5;

    protected ArrayList<File> octrees;
    private MiniPackedOctree cachedTree = null;
    private int cachedTreeIndex = 0;
    private boolean cachedTreeMutated = false;
    private int[] treeData;
    private int totalDepth;
    private int depth;
    private int size;

    private static final class NodeId implements Octree.NodeId {
        long nodeIndex;

        public NodeId(long nodeIndex) {
            this.nodeIndex = nodeIndex;
        }
    }

    public LargeDiskOctree(int depth) {
        this.totalDepth = FastMath.max(INTERN_OCTREE_DEPTH, depth);
        this.depth = this.totalDepth - INTERN_OCTREE_DEPTH;
        treeData = new int[DEFAULT_INITIAL_SIZE];

        octrees = new ArrayList<>(DEFAULT_INITIAL_SIZE);
        octrees.add(null);  // Index 0 is not used
    }

    /**
     * Minimize memory in preparation for the custom renderer.
     */
    protected void cleanup() {
        int[] newTree = new int[size];
        System.arraycopy(treeData, 0, newTree, 0, size);
        treeData = newTree;

        if (cachedTreeMutated) {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(octrees.get(cachedTreeIndex))))) {
                cachedTree.store(out);
            } catch (IOException e) {
                Log.error(e);
            }
        }

        cachedTree = null;
        cachedTreeIndex = 0;
        cachedTreeMutated = false;

        System.gc();
    }

    private int findSpace() {
        if (size + 8 <= treeData.length) {
            int index = size;
            size += 8;
            return index;
        }

        int[] newArray = new int[(int) FastMath.ceil(treeData.length * ARRAY_RESIZE_MULTIPLIER)];
        System.arraycopy(treeData, 0, newArray, 0, size);
        treeData = newArray;

        int index = size;
        size += 8;
        return index;
    }

    private void subdivideNode(int nodeIndex) {
        int firstChildIndex = findSpace();

        for (int i = 0; i < 8; i++) {
            treeData[firstChildIndex + i] = treeData[nodeIndex];
        }

        treeData[nodeIndex] = firstChildIndex;
    }

    /**
     * Save the current octree and swap to the octree at (x, y, z).
     */
    private void switchOctrees(int x, int y, int z) throws IOException {
        // Calculate which node
        int nodeIndex = 0;
        int level = totalDepth;
        while (treeData[nodeIndex] > 0) {
            level--;
            int lx = 1 & (x >>> level);
            int ly = 1 & (y >>> level);
            int lz = 1 & (z >>> level);
            nodeIndex = treeData[nodeIndex] + ((lx << 2) | (ly << 1) | lz);
        }
        int data = -treeData[nodeIndex];

        // Same octree, skip
        if (data != 0 && data == cachedTreeIndex) {
            return;
        }

        // Different octree, save the current octree if necessary
        if (cachedTreeMutated) {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(octrees.get(cachedTreeIndex))))) {
                cachedTree.store(out);
            }
        }

        cachedTreeMutated = false;

        // Create new octree
        if (data == 0) {
            // Subdivide and traverse one more level
            subdivideNode(nodeIndex);
            level--;
            int lx = 1 & (x >>> level);
            int ly = 1 & (y >>> level);
            int lz = 1 & (z >>> level);
            nodeIndex = treeData[nodeIndex] + ((lx << 2) | (ly << 1) | lz);

            // Create new file
            File octreeF = File.createTempFile("largediskoctree", ".bin");
            octreeF.deleteOnExit();
            int index = octrees.size();
            treeData[nodeIndex] = -index;
            octrees.add(octreeF);

            // Create new octree
            cachedTree = new MiniPackedOctree(INTERN_OCTREE_DEPTH, new Vector3(0, 0, 0));
            cachedTreeIndex = index;
            cachedTreeMutated = true;

            return;
        }

        // Load existing octree
        int index = -treeData[nodeIndex];
        File octreeF = octrees.get(index);
        cachedTree = MiniPackedOctree.load(new DataInputStream(new FastBufferedInputStream(new GZIPInputStream(new FileInputStream(octreeF)))));
        cachedTreeIndex = index;
    }

    /**
     * Returns if the location (x, y, z) is empty.
     */
    private boolean emptyAt(int x, int y, int z) {
        int nodeIndex = 0;
        int level = totalDepth;
        while (treeData[nodeIndex] > 0) {
            level--;
            int lx = 1 & (x >>> level);
            int ly = 1 & (y >>> level);
            int lz = 1 & (z >>> level);
            nodeIndex = treeData[nodeIndex] + ((lx << 2) | (ly << 1) | lz);
        }

        return treeData[nodeIndex] == 0;
    }

    @Override
    public void set(int type, int x, int y, int z) {
        try {
            switchOctrees(x, y, z);
        } catch (IOException e) {
            Log.error(e);
            return;
        }

        cachedTreeMutated = true;
        cachedTree.set(type, x, y, z);
    }

    @Override
    public void set(Octree.Node data, int x, int y, int z) {
        this.set(data.type, x, y, z);
    }

    @Override
    public Octree.Node get(int x, int y, int z) {
        if (emptyAt(x, y, z)) return new Octree.Node(0);

        try {
            switchOctrees(x, y, z);
        } catch (IOException e) {
            Log.error(e);
            return null;
        }

        return new Octree.Node(cachedTree.get(x, y, z));
    }

    @Override
    public Material getMaterial(int x, int y, int z, BlockPalette palette) {
        return palette.get(this.get(x, y, z).type);
    }

    @Override
    public void store(DataOutputStream output) throws IOException {

    }

    @Override
    public int getDepth() {
        return this.totalDepth;
    }

    @Override
    public long nodeCount() {
        long sum = 0;
        sum += countNodes(0);
        sum += octrees.stream().mapToLong(octreeF -> {
            try {
                return MiniPackedOctree.load(new DataInputStream(new FastBufferedInputStream(new GZIPInputStream(new FileInputStream(octreeF))))).nodeCount();
            } catch (IOException e) {
                Log.error(e);
                return 1;
            }
        }).sum();
        return sum;
    }

    private long countNodes(int nodeIndex) {
        if (treeData[nodeIndex] > 0) {
            long total = 1;
            for (int i = 0; i < 8; i++) {
                total += countNodes(treeData[nodeIndex] + i);
            }
            return total;
        }
        return treeData[nodeIndex] == 0 ? 1 : 0;
    }

    @Override
    public Octree.NodeId getRoot() {
        return new NodeId(0);
    }

    @Override
    public boolean isBranch(Octree.NodeId node) {
        return false;
    }

    @Override
    public Octree.NodeId getChild(Octree.NodeId parent, int childNo) {
        return null;
    }

    @Override
    public int getType(Octree.NodeId node) {
        return 0;
    }

    @Override
    public int getData(Octree.NodeId node) {
        return 0;
    }

    @Override
    public void getWithLevel(IntIntMutablePair outTypeAndLevel, int x, int y, int z) {
        int nodeIndex = 0;
        int level = totalDepth;
        while (treeData[nodeIndex] > 0) {
            level--;
            int lx = 1 & (x >>> level);
            int ly = 1 & (y >>> level);
            int lz = 1 & (z >>> level);
            nodeIndex = treeData[nodeIndex] + ((lx << 2) | (ly << 1) | lz);
        }

        // Empty
        if (treeData[nodeIndex] == 0) {
            outTypeAndLevel.right(level).left(0);
            return;
        }

        level = totalDepth - level;
        try {
            switchOctrees(x, y, z);
        } catch (IOException e) {
            Log.error(e);
            return;
        }

        cachedTree.getWithLevel(outTypeAndLevel, x, y, z);
        outTypeAndLevel.right(outTypeAndLevel.rightInt() + level);
    }
}
