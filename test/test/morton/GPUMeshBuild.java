/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package test.morton;

import cl.core.data.CPoint3;
import cl.core.kernel.CLSource;
import cl.shapes.CMesh;
import coordinate.parser.obj.OBJParser;
import coordinate.struct.FloatStruct;
import coordinate.struct.StructFloatArray;
import coordinate.struct.IntStruct;
import coordinate.struct.StructIntArray;
import org.jocl.CL;
import wrapper.core.CBufferFactory;
import wrapper.core.CKernel;
import static wrapper.core.CMemory.READ_WRITE;
import wrapper.core.OpenCLPlatform;
import wrapper.core.buffer.CFloatBuffer;
import wrapper.core.buffer.CIntBuffer;
import wrapper.util.CLFileReader;

/**
 *
 * @author user
 */
public class GPUMeshBuild {
    public static void main(String... args)
    {
        String source1 = CLFileReader.readFile(CLSource.class, "Common.cl");        
        String source2 = CLFileReader.readFile(GPUBVHNodeTest.class, "GPUBuildMesh.cl");
        
        CL.setExceptionsEnabled(true);
        OpenCLPlatform configuration = OpenCLPlatform.getDefault(source1, source2);
        
        OBJParser parser = new OBJParser();
        CMesh mesh = new CMesh(configuration);       
        parser.read("C:\\Users\\user\\Documents\\Scene3d\\simplebox\\boxes.obj", mesh);
        mesh.initCLBuffers();
        
        int leafS = mesh.getCount();              // n
        int nodeS = leafS - 1;                          // n - 1 
        int tSize = nodeS + leafS;                      //2n - 1        
        int[] intflags = new int[nodeS];
        StructIntArray<BVHNode>       nodes  = new StructIntArray<>(BVHNode.class, tSize);
        StructFloatArray<BoundingBox> bounds = new StructFloatArray<>(BoundingBox.class, tSize);        
        StructIntArray<MortonPrimitive> mortonPrimitives = new StructIntArray<>(MortonPrimitive.class, leafS); 
        
        //init mesh
        CFloatBuffer cpoints = mesh.clPoints();
        CIntBuffer cfaces = mesh.clFaces();
        CIntBuffer csize = mesh.clSize(); 
                
        //init morton array
        CIntBuffer cmortons = CBufferFactory.wrapInt("mortons", configuration.context(), configuration.queue(), mortonPrimitives.getArray(), READ_WRITE);        
        //init flags
        CIntBuffer cflags = CBufferFactory.wrapInt("flags", configuration.context(), configuration.queue(), intflags, READ_WRITE);
        //init nodes 
        CIntBuffer cnodes          = CBufferFactory.wrapInt("nodes", configuration.context(), configuration.queue(), nodes.getArray(), READ_WRITE);        
        //init bounds
        CFloatBuffer cbounds         = CBufferFactory.wrapFloat("bounds", configuration.context(), configuration.queue(), bounds.getArray(), READ_WRITE);
        
        //calculate mortons
        CKernel calculateMortonKernel = configuration.program().createKernel("calculateMorton", cpoints, cfaces, csize, cmortons); 
        configuration.queue().put1DRangeKernel(calculateMortonKernel, leafS, 1);
        
        cmortons.transferFromDeviceToBuffer(configuration.queue());        
        sort(mortonPrimitives);
        cmortons.transferFromBufferToDevice(configuration.queue());
        
        //emit bvh tree
        CKernel emitHierarchyKernel = configuration.program().createKernel("emitHierarchy", cpoints, cfaces, csize, cmortons, cnodes, cbounds); 
        configuration.queue().put1DRangeKernel(emitHierarchyKernel, leafS, 1);
                       
        //refit bounds for nodes
        CKernel refitBoundsKernel = configuration.program().createKernel("refitBounds", csize, cflags, cnodes, cbounds); 
        configuration.queue().put1DRangeKernel(refitBoundsKernel, leafS, 1);
         cnodes.transferFromDeviceToBuffer(configuration.queue());
        cbounds.transferFromDeviceToBuffer(configuration.queue());
        
        for(int i =0; i< nodes.size(); i++)
            System.out.println(nodes.get(i));
    }
    
    public static class BVHNode extends IntStruct
    {
        int bound;
        int sibling;
        int left;
        int right;
        int parent;
        int isLeaf;
        int child;
        
        @Override
        public void initFromGlobalArray() {
            int[] globalArray = getGlobalArray();
            if(globalArray == null)
                return;
            int globalArrayIndex = getGlobalArrayIndex();
            
            bound   = globalArray[globalArrayIndex + 0];
            sibling = globalArray[globalArrayIndex + 1];
            left    = globalArray[globalArrayIndex + 2];
            right   = globalArray[globalArrayIndex + 3];
            parent  = globalArray[globalArrayIndex + 4];
            isLeaf  = globalArray[globalArrayIndex + 5];
            child   = globalArray[globalArrayIndex + 6];
        }

        @Override
        public int[] getArray() {
            return new int[]{bound, sibling, left, right, parent, isLeaf, child};
        }

        @Override
        public int getSize() {
            return 7;
        } 
        
        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder(); 
            builder.append("bounds   ").append(bound).append("\n");
            builder.append("parent   ").append(parent).append("\n");
            builder.append("sibling  ").append(sibling).append("\n");
            builder.append("left     ").append(left).append(" right     ").append(right).append("\n");
            builder.append("is leaf  ").append(isLeaf).append("\n");
            builder.append("child no ").append(child).append("\n");    
            return builder.toString();
        }
    }
    
    public static class BoundingBox extends FloatStruct
    {
        CPoint3 minimum;
        CPoint3 maximum;

        public BoundingBox()
        {
            this.minimum = new CPoint3();
            this.maximum = new CPoint3();
        }

        @Override
        public void initFromGlobalArray() {
            float[] globalArray = getGlobalArray();
            if(globalArray == null)
                return;
            int globalArrayIndex = getGlobalArrayIndex();

            minimum.x   = globalArray[globalArrayIndex + 0];
            minimum.y   = globalArray[globalArrayIndex + 1];
            minimum.z   = globalArray[globalArrayIndex + 2];
            maximum.x   = globalArray[globalArrayIndex + 4];
            maximum.y   = globalArray[globalArrayIndex + 5];
            maximum.z   = globalArray[globalArrayIndex + 6];
        }

        @Override
        public float[] getArray() {
            return new float[]{minimum.x, minimum.y, minimum.z, 0,
                               maximum.x, maximum.y, maximum.z, 0};
        }

        @Override
        public int getSize() {
            return 8;
        } 

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder(); 
            builder.append("Bounding Box").append("\n");
            builder.append("  ").append("minimum: ").append(minimum).append("\n");
            builder.append("  ").append("maximum: ").append(maximum).append("\n");
            return builder.toString();
        }
    }    
    
    public static class MortonPrimitive extends IntStruct
    {
        public int mortonCode;
        public int primitiveIndex;

        @Override
        public void initFromGlobalArray() {
            int[] globalArray = getGlobalArray();
            if(globalArray == null)
                return;
            int globalArrayIndex = getGlobalArrayIndex();
            
            mortonCode      = globalArray[globalArrayIndex + 0];     
            primitiveIndex  = globalArray[globalArrayIndex + 1];                   
        }

        @Override
        public int[] getArray() {
            return new int[]{mortonCode, primitiveIndex};
        }

        @Override
        public int getSize() {
            return 2;
        }
        
        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("primitive index - ").append(primitiveIndex).append(" ");
            builder.append("morton code     - ").append(mortonCode);
            return builder.toString();
        }
    }
    
    public static void sort(StructIntArray<MortonPrimitive> mortonPrimitives)
    {
        StructIntArray<MortonPrimitive> temp = new StructIntArray<>(MortonPrimitive.class, mortonPrimitives.size());
        int bitsPerPass = 6;
        int nBits = 30;
        int nPasses = nBits/bitsPerPass;
        
        for(int pass = 0; pass < nPasses; ++pass)
        {
            int lowBit = pass * bitsPerPass;
            
            StructIntArray<MortonPrimitive> in  = ((pass & 1) == 1) ? temp              : mortonPrimitives;
            StructIntArray<MortonPrimitive> out = ((pass & 1) == 1) ? mortonPrimitives  : temp;
            
            int nBuckets = 1 << bitsPerPass;
            int bucketCount[] = new int[nBuckets];
            int bitMask = (1 << bitsPerPass) - 1;
            
            for(int i = 0; i<in.size(); i++)
            {
                MortonPrimitive p = in.get(i);
                int bucket = (p.mortonCode >> lowBit) & bitMask; 
                ++bucketCount[bucket];
            }
            
            int[] outIndex = new int[nBuckets];
            for(int i = 1; i < nBuckets; ++i)
                outIndex[i] = outIndex[i - 1] + bucketCount[i-1];
            
            for(int i = 0; i<in.size(); i++)
            {
                MortonPrimitive p = in.get(i);
                int bucket = (p.mortonCode >> lowBit) & bitMask;
                out.set(p, outIndex[bucket]++);
            }            
        }
        
        if((nPasses & 1) == 1) System.arraycopy(temp.getArray(), 0, mortonPrimitives.getArray(), 0, temp.getArray().length);    
    }
}
