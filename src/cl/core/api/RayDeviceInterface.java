/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cl.core.api;

import bitmap.display.BlendDisplay;
import cl.core.CAccelerator;
import cl.core.CBoundingBox;
import cl.core.CCamera;
import cl.core.CCameraModel;
import cl.shapes.CMesh;
import coordinate.parser.attribute.MaterialT;
import java.nio.Buffer;
import wrapper.core.CallBackFunction;

/**
 *
 * @author user
 * @param <A>
 * @param <B>
 * @param <D>
 * @param <M>
 */
public interface RayDeviceInterface <A extends MambaAPIInterface, B extends Buffer, D extends BlendDisplay, M extends MaterialT> {    
    public enum DeviceBuffer{
        IMAGE_BUFFER,
        GROUP_BUFFER,
        RENDER_BUFFER
    }
    

    enum ShadeType{
        NORMAL_SHADE,
        RAYTRACE_SHADE
    };
        
    public void setAPI(A api);
    
    public void set(CMesh mesh, CAccelerator bvhBuild);        
    public void setGlobalSize(int globalSize);
    public void setLocalSize(int localSize);
    
    default void start(){execute();}            
    public void execute();    
    public void pause();
    public void stop();
    public void resume();
    
    public boolean isPaused();
    public boolean isRunning();
    public boolean isStopped();
    
    public void readBuffer(DeviceBuffer name, CallBackFunction<B> callback);   
    
    public void setMaterial(int index, M material);
    public void updateCamera();    
    public void setCamera(CCamera camera);    
    public CCameraModel getCameraModel(); 
    public int getTotalSize();
    public CBoundingBox getPriorityBound();
    public void setPriorityBound(CBoundingBox bound);
    public CBoundingBox getBound(); 
    public CBoundingBox getGroupBound(int value);
    
    public void setShadeType(ShadeType type);
    public ShadeType getShadeType();
    
   
}
