/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cl.core.device;

import bitmap.display.BlendDisplay;
import cl.core.CAccelerator;
import cl.core.CBoundingBox;
import cl.core.CCamera;
import cl.core.CCameraModel;
import static cl.core.api.MambaAPIInterface.DeviceType.RAYTRACE;
import static cl.core.api.MambaAPIInterface.ImageType.RAYTRACE_IMAGE;
import cl.core.api.RayDeviceInterface;
import static cl.core.api.RayDeviceInterface.DeviceBuffer.GROUP_BUFFER;
import static cl.core.api.RayDeviceInterface.DeviceBuffer.IMAGE_BUFFER;
import cl.core.data.CPoint3;
import cl.core.data.CVector3;
import cl.main.TracerAPI;
import cl.shapes.CMesh;
import coordinate.model.OrientationModel;
import coordinate.parser.attribute.MaterialT;
import filesystem.core.OutputFactory;
import java.nio.IntBuffer;
import thread.model.LambdaThread;
import wrapper.core.CKernel;
import static wrapper.core.CMemory.READ_ONLY;
import static wrapper.core.CMemory.READ_WRITE;
import wrapper.core.CallBackFunction;
import wrapper.core.buffer.CFloatBuffer;
import wrapper.core.buffer.CIntBuffer;
import static cl.core.api.MambaAPIInterface.ImageType.ALL_RAYTRACE_IMAGE;
import static cl.core.api.RayDeviceInterface.ShadeType.NORMAL_SHADE;
import static cl.core.api.RayDeviceInterface.ShadeType.RAYTRACE_SHADE;
import cl.core.data.CPoint2;
import cl.core.data.struct.CIntersection;
import cl.core.data.struct.CMaterial;
import cl.core.data.struct.CRay;
import wrapper.core.buffer.CStructTypeBuffer;

/**
 *
 * @author user
 */
public class RayDeviceMesh implements RayDeviceInterface<TracerAPI, IntBuffer, BlendDisplay, MaterialT>{
    private TracerAPI api;
        
    CCameraModel cameraModel = new CCameraModel(new CPoint3(0, 0, 9), new CPoint3(), new CVector3(0, 1, 0), 45);
    
    CIntBuffer hitCount = null;
    
    CIntBuffer startNode = null;
    
    //image variables
    CIntBuffer imageBuffer = null;
    CIntBuffer groupBuffer = null;
       
    //group index an bound for selection
    CIntBuffer groupIndex = null;
    CFloatBuffer groupBound = null;
    
    //viewport variables
    CStructTypeBuffer<CCamera> cameraBuffer = null;
    
    //Ray & intersects
    CStructTypeBuffer<CRay> raysBuffer;
    CStructTypeBuffer<CIntersection> isectBuffer;
         
    //global count
    CIntBuffer count = null;
    
    //global and local size
    private int globalSize, localSize;
    
    //For ray tracing    
    CKernel initGroupBufferKernel = null;
    CKernel initCameraRaysKernel = null;    
    CKernel intersectPrimitivesKernel = null;
    CKernel fastShadeKernel = null;
    CKernel backgroundShadeKernel = null;
    CKernel updateNormalShadeImageKernel = null;
    CKernel updateGroupbufferShadeImageKernel = null;
    
    //iterate primitives to get bound of specific group
    CKernel findBoundKernel = null;
       
    //mesh and accelerator
    CMesh mesh;
    CAccelerator bvhBuild;
    
    //priority bound
    CBoundingBox priorityBound;
    
    //render thread
    LambdaThread raytraceThread = new LambdaThread();
    
    //Shade type
    ShadeType type = RAYTRACE_SHADE;
    
    public RayDeviceMesh()
    {
        
    }
    
    
    
    @Override
    public CCameraModel getCameraModel(){return cameraModel;}
    
    @Override
    public void execute()
    {     
       raytraceThread.startExecution(()-> {
            //execute pause             
            loop();
            raytraceThread.pauseExecution();       
        });      
    }
    
    private void loop()
    {
        if(cameraModel.isSynched(cameraBuffer.get(0)))
            raytraceThread.chill();       
            
        updateCamera();

        //reset to window size
        count.mapWriteValue(api.configurationCL().queue(), globalSize);

        api.configurationCL().queue().put1DRangeKernel(initGroupBufferKernel, globalSize, localSize);
        api.configurationCL().queue().put1DRangeKernel(initCameraRaysKernel, globalSize, localSize); 
        api.configurationCL().queue().put1DRangeKernel(intersectPrimitivesKernel, globalSize, localSize);    
        api.configurationCL().queue().put1DRangeKernel(backgroundShadeKernel, globalSize, localSize);          
        
        //shade type
        switch (type) {
            case RAYTRACE_SHADE:
                api.configurationCL().queue().put1DRangeKernel(fastShadeKernel, globalSize, localSize);      
                break;
            case NORMAL_SHADE:
                api.configurationCL().queue().put1DRangeKernel(updateNormalShadeImageKernel, globalSize, localSize); 
                break;
            default:
                throw new UnsupportedOperationException("shade type not supported yet.");
        }
        
        api.configurationCL().queue().put1DRangeKernel(updateGroupbufferShadeImageKernel, globalSize, localSize);

         /*
             Why implementing this makes opencl run faster?
            Probable answer is this... https://stackoverflow.com/questions/18471170/commenting-clfinish-out-makes-program-100-faster
        */  
                 
        api.configurationCL().queue().finish();

        //update image
        api.readImageFromDevice(RAYTRACE, ALL_RAYTRACE_IMAGE);

        
    }
    
    public void pauseRender()            
    {
        raytraceThread.pauseExecution();
    }
    
    public void stopRender()
    {
        raytraceThread.stopExecution();
    }
    
    public boolean isRenderPaused()
    {
        return raytraceThread.isPaused();
    }
    
    @Override
    public void updateCamera(){
        this.cameraBuffer.mapWriteBuffer(api.configurationCL().queue(), cameraStruct -> 
            {
                CCamera cam = cameraModel.getCameraStruct();
                cam.setDimension(new CPoint2(api.getImageSize(RAYTRACE_IMAGE)));                
                cameraStruct.set(cam, 0);
                
                OutputFactory.print("eye", cameraModel.position().toString());
                OutputFactory.print("dir", cameraModel.forward().toString());
                OutputFactory.print("fov", Float.toString(cameraModel.fov));
                
            });
    }
    
    public void setMaterial(int index, CMaterial material)
    {    
        mesh.setMaterial(index, material);
    }
    
    @Override
    public int getTotalSize(){return globalSize;}
    
    @Override
    public CBoundingBox getBound()
    {
        return bvhBuild.getBound();
    }
    
    @Override
    public CBoundingBox getPriorityBound()
    {
        return priorityBound;
    }
    
    @Override
    public CBoundingBox getGroupBound(int value)
    {
        groupBound.mapWriteBuffer(api.configurationCL().queue(), buffer -> {
           buffer.put(new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY});
       });
        groupIndex.setArray(api.configurationCL().queue(), value);
       
       api.configurationCL().queue().put1DRangeKernel(findBoundKernel, mesh.clSize().get(0), 1);
       api.configurationCL().queue().finish(); // not really necessary
       
       CPoint3 min = new CPoint3();
       CPoint3 max = new CPoint3();
       
       groupBound.mapReadBuffer(api.configurationCL().queue(), buffer -> {           
           min.x = buffer.get(0); min.y = buffer.get(1); min.z = buffer.get(2);
           max.x = buffer.get(3); max.y = buffer.get(4); max.z = buffer.get(5);   
           
           //System.out.println(String.format("(%8.2f, %8.2f, %8.2f) to (%8.2f, %8.2f, %8.2f)", buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3), buffer.get(4), buffer.get(5)));
           
       });       
       return new CBoundingBox(min, max);
    }
    
    public void reposition(CBoundingBox box)
    {
        OrientationModel<CPoint3, CVector3, CRay, CBoundingBox> orientation = new OrientationModel(CPoint3.class, CVector3.class);
        orientation.repositionLocation(cameraModel, box);
    }
       
    @Override
    public void setAPI(TracerAPI api) {
        this.api = api;
        this.globalSize = api.getGlobalSizeForDevice(RAYTRACE);
        this.localSize = 100;
        
         //Init constant global variables, except mesh that is loaded after mesh is uploaded
        this.startNode          = api.allocIntValue("startnode", 0, READ_WRITE);
        this.hitCount           = api.allocIntValue("hitCount", 0, READ_WRITE);
        this.imageBuffer        = api.allocInt("image", globalSize, READ_WRITE);
        this.groupBuffer        = api.allocInt("group", globalSize, READ_WRITE);
        this.isectBuffer        = api.allocStructType("intersections",  CIntersection.class,    globalSize, READ_WRITE);
        this.raysBuffer         = api.allocStructType("rays",           CRay.class,             globalSize, READ_WRITE);
        this.cameraBuffer       = api.allocStructType("camera",         CCamera.class,     1,          READ_ONLY);
        this.count              = api.allocIntValue("count", 0, READ_WRITE);
        this.groupIndex         = api.allocIntValue("groupIndex", 0, READ_ONLY);
        this.groupBound         = api.allocFloat("groupBound", 6 , READ_WRITE);             
    }

    @Override
    public void set(CMesh mesh, CAccelerator bvhBuild) {
        this.mesh = mesh;
        this.bvhBuild = bvhBuild;
        this.priorityBound = bvhBuild.getBound();
        
        startNode.mapWriteValue(api.configurationCL().queue(), bvhBuild.getStartNodeIndex());
        
        //Set camera new position
        OrientationModel<CPoint3, CVector3, CRay, CBoundingBox> orientation = new OrientationModel(CPoint3.class, CVector3.class);
        orientation.reposition(cameraModel, mesh.getBound());
        
        initGroupBufferKernel               = api.createKernel("InitIntData_1", groupBuffer);
        initCameraRaysKernel                = api.createKernel("InitCameraRayData", cameraBuffer, raysBuffer);
        intersectPrimitivesKernel           = api.createIntersectionKernel("IntersectPrimitives", raysBuffer, isectBuffer, count, mesh, bvhBuild, startNode);
        fastShadeKernel                     = api.createKernel("fastShade", mesh.clMaterials(), isectBuffer, imageBuffer);
        backgroundShadeKernel               = api.createKernel("backgroundShade", isectBuffer, cameraBuffer, imageBuffer);
        updateNormalShadeImageKernel        = api.createKernel("updateNormalShadeImage", isectBuffer, cameraBuffer, imageBuffer);
        updateGroupbufferShadeImageKernel   = api.createKernel("updateGroupbufferShadeImage", isectBuffer, cameraBuffer, groupBuffer);
        findBoundKernel                     = api.createKernel("findBound", groupIndex, mesh.clPoints(), mesh.clNormals(), mesh.clFaces(), mesh.clSize(), groupBound);        
        
    }

    @Override
    public void setLocalSize(int localSize) {
        this.localSize = localSize;
    }

    @Override
    public void readBuffer(DeviceBuffer name, CallBackFunction<IntBuffer> callback) {
        if(name == IMAGE_BUFFER)
            imageBuffer.mapReadBuffer(api.configurationCL().queue(), callback);
        else if(name == GROUP_BUFFER)
            groupBuffer.mapReadBuffer(api.configurationCL().queue(), callback);
    }

    @Override
    public void setMaterial(int index, MaterialT material) {        
        CMaterial cmaterial = new CMaterial();        
        cmaterial.setMaterial(material);         
        mesh.setMaterial(index, cmaterial);
    }

    @Override
    public void pause() {
        raytraceThread.pauseExecution();
    }

    @Override
    public void stop() {
        raytraceThread.stopExecution();
    }

    @Override
    public boolean isPaused() {
        return raytraceThread.isPaused();
    }

    @Override
    public boolean isRunning() {
        return !raytraceThread.isPaused();
    }

    @Override
    public void setCamera(CCamera camera) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void resume() {
        raytraceThread.resumeExecution();
    }

    @Override
    public void setGlobalSize(int globalSize) {
        this.globalSize = globalSize;
    }

    @Override
    public boolean isStopped() {
        return raytraceThread.isTerminated();
    }

    @Override
    public void setShadeType(ShadeType type) {
        this.type = type;
    }

    @Override
    public ShadeType getShadeType() {
        return type;
    }

    @Override
    public void setPriorityBound(CBoundingBox bound) {
        this.priorityBound = bound;
    }
}
