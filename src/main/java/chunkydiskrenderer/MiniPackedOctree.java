package chunkydiskrenderer;

import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.block.Block;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.math.Ray;
import se.llbit.math.Vector3;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * This is an internal octree based off of PackedOctree.
 */
public class MiniPackedOctree {
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;
    private static final int DEFAULT_INITIAL_SIZE = 64;
    private static final double ARRAY_RESIZE_MULTIPLIER = 1.5;

    public final int depth;
    public Vector3 center;
    public int[] treeData;
    public int size;
    private int freeHead;

    public MiniPackedOctree(int depth, Vector3 center) {
        this.depth = depth;
        treeData = new int[DEFAULT_INITIAL_SIZE];
        treeData[0] = 0;
        size = 1;
        freeHead = -1;
        this.center = center;
    }

    private int findSpace() {
        if (freeHead != -1) {
            int index = freeHead;
            freeHead = treeData[freeHead];
            return index;
        }

        if (size + 8 <= treeData.length) {
            int index = size;
            size += 8;
            return index;
        }

        long newSize = (long) FastMath.ceil(treeData.length * ARRAY_RESIZE_MULTIPLIER);
        if (newSize > MAX_ARRAY_SIZE) {
            if (MAX_ARRAY_SIZE - size > 8) {
                newSize = MAX_ARRAY_SIZE;
            } else {
                throw new RuntimeException("Octree too big");
            }
        }

        int[] newArray = new int[(int) newSize];
        System.arraycopy(treeData, 0, newArray, 0, size);
        treeData = newArray;

        int index = size;
        size += 8;
        return index;
    }

    private void freeSpace(int index) {
        treeData[index] = freeHead;
        freeHead = index;
    }

    private void subdivideNode(int nodeIndex) {
        int firstChildIndex = findSpace();

        for (int i = 0; i < 8; i++) {
            treeData[firstChildIndex + i] = treeData[nodeIndex];
        }

        treeData[nodeIndex] = firstChildIndex;
    }

    private void mergeNode(int nodeIndex, int typeNegation) {
        int childrenIndex = treeData[nodeIndex];
        freeSpace(childrenIndex);
        treeData[nodeIndex] = typeNegation;
    }

    private boolean nodeEquals(int firstNodeIndex, int secondNodeIndex) {
        boolean firstIsBranch = treeData[firstNodeIndex] > 0;
        boolean secondIsBranch = treeData[secondNodeIndex] > 0;
        return (firstIsBranch && secondIsBranch) || treeData[firstNodeIndex] == treeData[secondNodeIndex];
    }

    public void set(int type, int x, int y, int z) {
        int[] parents = new int[depth];
        int nodeIndex = 0;
        int position;

        for (int i = depth-1; i >= 0; i--) {
            parents[i] = nodeIndex;

            if (treeData[nodeIndex] == -type) {
                return;
            }

            if (treeData[nodeIndex] <= 0) {
                subdivideNode(nodeIndex);
            }

            int xbit = 1 & (x >> i);
            int ybit = 1 & (y >> i);
            int zbit = 1 & (z >> i);
            position = (xbit << 2) | (ybit << 1) | zbit;
            nodeIndex = treeData[nodeIndex] + position;
        }

        treeData[nodeIndex] = -type;

        for (int i = 0; i < depth; i++) {
            int parentIndex = parents[i];

            boolean allSame = true;
            for (int j = 0; j < 8; j++) {
                int childIndex = treeData[parentIndex] + j;
                if (!nodeEquals(childIndex, nodeIndex)) {
                    allSame = false;
                    break;
                }
            }

            if (allSame) {
                mergeNode(parentIndex, treeData[nodeIndex]);
            } else {
                break;
            }
        }
    }

    public void getWithLevel(IntIntMutablePair outTypeAndLevel, int x, int y, int z) {
        int nodeIndex = 0;
        int level = depth;
        while(treeData[nodeIndex] > 0) {
            level -= 1;
            int lx = x >>> level;
            int ly = y >>> level;
            int lz = z >>> level;
            nodeIndex = treeData[nodeIndex] + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
        }
        outTypeAndLevel.left(-treeData[nodeIndex]).right(level);
    }

    public int get(int x, int y, int z) {
        int nodeIndex = 0;
        int level = depth;
        while(treeData[nodeIndex] > 0) {
            level -= 1;
            int lx = 1 & (x >>> level);
            int ly = 1 & (y >>> level);
            int lz = 1 & (z >>> level);
            nodeIndex = treeData[nodeIndex] + ((lx << 2) | (ly << 1) | lz);
        }
        return -treeData[nodeIndex];
    }

    public long nodeCount() {
        return countNodes(0);
    }

    private long countNodes(int nodeIndex) {
        if (treeData[nodeIndex] > 0) {
            long total = 1;
            for (int i = 0; i < 8; i++) {
                total += countNodes(treeData[nodeIndex] + i);
            }
            return total;
        }
        return 1;
    }

    public boolean intersect(LargeDiskRenderer.CacheRay ray, BlockPalette palette, Scene scene) {
        float temp = ray.distance;

        if (!isInside(ray) && !enterOctree(ray))
            return false;

        float distance = ray.distance;
        ray.distance = temp;

        float invDx = 1 / ray.direction.x;
        float invDy = 1 / ray.direction.y;
        float invDz = 1 / ray.direction.z;
        float offsetX = -ray.origin.x * invDx;
        float offsetY = -ray.origin.y * invDy;
        float offsetZ = -ray.origin.z * invDz;

        IntIntMutablePair typeAndLevel = new IntIntMutablePair(0, 0);
        while (true) {
            // Already have a closer intersection
            if (ray.distance < distance) return false;

            int x = (int) (ray.origin.x + ray.direction.x * (distance + Ray.OFFSET));
            int y = (int) (ray.origin.y + ray.direction.y * (distance + Ray.OFFSET));
            int z = (int) (ray.origin.z + ray.direction.z * (distance + Ray.OFFSET));

            int lx = x >>> depth;
            int ly = x >>> depth;
            int lz = x >>> depth;

            if (lx != 0 || ly != 0 || lz != 0)
                return false;

            getWithLevel(typeAndLevel, x, y, z);
            int type = typeAndLevel.leftInt();
            int level = typeAndLevel.rightInt();

            lx = x >>> level;
            ly = y >>> level;
            lz = z >>> level;

            Block currentBlock = palette.get(type);
            if (!currentBlock.invisible) {
                Ray rayTest = new Ray();
                rayTest.setCurrentMaterial(currentBlock);
                rayTest.o.x = ray.origin.x + ray.direction.x * (distance + Ray.OFFSET);
                rayTest.o.y = ray.origin.y + ray.direction.y * (distance + Ray.OFFSET);
                rayTest.o.z = ray.origin.z + ray.direction.z * (distance + Ray.OFFSET);
                rayTest.d.x = ray.direction.x;
                rayTest.d.y = ray.direction.y;
                rayTest.d.z = ray.direction.z;
                rayTest.n.x = ray.normal.x;
                rayTest.n.y = ray.normal.y;
                rayTest.n.z = ray.normal.z;
                if (currentBlock.intersect(rayTest, scene)) {
                    ray.distance = (float) (rayTest.distance + distance);
                    ray.color.x = (float) rayTest.color.x;
                    ray.color.y = (float) rayTest.color.y;
                    ray.color.z = (float) rayTest.color.z;
                    ray.emittance.x = (float) rayTest.emittance.x;
                    ray.emittance.y = (float) rayTest.emittance.y;
                    ray.emittance.z = (float) rayTest.emittance.z;
                    return true;
                }
            }

            // No intersection, exit current octree leaf.
            int nx = 0, ny = 0, nz = 0;
            float tNear = Float.POSITIVE_INFINITY;

            float t = (lx << level) * invDx + offsetX;
            if (t > distance + Ray.EPSILON) {
                tNear = t;
                nx = 1;
            }
            t = ((lx + 1) << level) * invDx + offsetX;
            if (t < tNear && t > distance + Ray.EPSILON) {
                tNear = t;
                nx = -1;
            }

            t = (ly << level) * invDy + offsetY;
            if (t < tNear && t > distance + Ray.EPSILON) {
                tNear = t;
                ny = 1;
                nx = 0;
            }
            t = ((ly + 1) << level) * invDy + offsetY;
            if (t < tNear && t > distance + Ray.EPSILON) {
                tNear = t;
                ny = -1;
                nx = 0;
            }

            t = (lz << level) * invDz + offsetZ;
            if (t < tNear && t > distance + Ray.EPSILON) {
                tNear = t;
                nz = 1;
                nx = ny = 0;
            }
            t = ((lz + 1) << level) * invDz + offsetZ;
            if (t < tNear && t > distance + Ray.EPSILON) {
                tNear = t;
                nz = -1;
                nx = ny = 0;
            }

            ray.normal.x = nx;
            ray.normal.y = ny;
            ray.normal.z = nz;

            distance += tNear;
        }
    }

    private boolean isInside(LargeDiskRenderer.CacheRay ray) {
        int x = (int) ray.origin.x;
        int y = (int) ray.origin.y;
        int z = (int) ray.origin.z;

        int lx = x >>> depth;
        int ly = y >>> depth;
        int lz = z >>> depth;

        return lx == 0 && ly == 0 && lz == 0;
    }

    private boolean enterOctree(LargeDiskRenderer.CacheRay ray) {
        float nx, ny, nz;
        float octree_size = 1 << depth;

        // AABB intersection with the octree boundary
        float tMin, tMax;
        float invDirX = 1 / ray.direction.x;
        if (invDirX >= 0) {
            tMin = -ray.origin.x * invDirX;
            tMax = (octree_size - ray.origin.x) * invDirX;

            nx = -1;
            ny = nz = 0;
        } else {
            tMin = (octree_size - ray.origin.x) * invDirX;
            tMax = -ray.origin.x * invDirX;

            nx = 1;
            ny = nz = 0;
        }

        float tYMin, tYMax;
        float invDirY = 1 / ray.direction.y;
        if (invDirY >= 0) {
            tYMin = -ray.origin.y * invDirY;
            tYMax = (octree_size - ray.origin.y) * invDirY;
        } else {
            tYMin = (octree_size - ray.origin.y) * invDirY;
            tYMax = -ray.origin.y * invDirY;
        }

        if ((tMin > tYMax) || (tYMin > tMax))
            return false;

        if (tYMin > tMin) {
            tMin = tYMin;

            ny = -FastMath.signum(ray.direction.y);
            nx = nz = 0;
        }

        if (tYMax < tMax)
            tMax = tYMax;

        float tZMin, tZMax;
        float invDirZ = 1 / ray.direction.z;
        if (invDirZ >= 0) {
            tZMin = -ray.origin.z * invDirZ;
            tZMax = (octree_size - ray.origin.z) * invDirZ;
        } else {
            tZMin = (octree_size - ray.origin.z) * invDirZ;
            tZMax = -ray.origin.z * invDirZ;
        }

        if ((tMin > tZMax) || (tZMin > tMax))
            return false;

        if (tZMin > tMin) {
            tMin = tZMin;

            nz = -FastMath.signum(ray.direction.z);
            nx = ny = 0;
        }

        if (tMin < 0)
            return false;

        ray.normal.x = nx;
        ray.normal.y = ny;
        ray.normal.z = nz;
        ray.distance += tMin;
        return true;
    }

    /**
     * Store this octree into an output stream. Make sure this octree is finalized before storing.
     */
    public void store(DataOutputStream output) throws IOException {
        output.writeInt(depth);
        output.writeInt(size);
        output.writeDouble(center.x);
        output.writeDouble(center.y);
        output.writeDouble(center.z);
        for (int i = 0; i < size; i++)
            output.writeInt(treeData[i]);
    }

    public static MiniPackedOctree load(DataInputStream in) throws IOException {
        int depth = in.readInt();
        int size = in.readInt();
        Vector3 center = new Vector3(in.readDouble(), in.readDouble(), in.readDouble());

        MiniPackedOctree tree = new MiniPackedOctree(depth, center);
        tree.size = size;
        tree.treeData = new int[size];
        for (int i = 0; i < size; i++)
            tree.treeData[i] = in.readInt();

        return tree;
    }
}
