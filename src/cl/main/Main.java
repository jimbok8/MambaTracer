/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cl.main;

import bitmap.display.BlendDisplay;
import static cl.core.api.MambaAPIInterface.DeviceType.RAYTRACE;
import static cl.core.api.MambaAPIInterface.ImageType.OVERLAY_IMAGE;
import static cl.core.api.MambaAPIInterface.ImageType.RAYTRACE_IMAGE;
import static cl.core.api.MambaAPIInterface.ImageType.RENDER_IMAGE;
import cl.core.api.RayDeviceInterface;
import static cl.core.api.RayDeviceInterface.ShadeType.NORMAL_SHADE;
import static cl.core.api.RayDeviceInterface.ShadeType.RAYTRACE_SHADE;
import javafx.application.Application;
import static javafx.application.Application.launch;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 *
 * @author user
 */
public class Main extends Application {
    public static void main(String... args)
    {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("RenderWindow.fxml"));
        Parent root = loader.load();
        
        //create api and set display
        TracerAPI api = new TracerAPI();   //api creates devices inside    
        api.setBlendDisplay(new BlendDisplay(RAYTRACE_IMAGE.name(),RENDER_IMAGE.name(), OVERLAY_IMAGE.name()));
        
        //set controller (which sets display inside)
        RenderWindowController controller = (RenderWindowController)loader.getController();        
        api.set("controller", controller);
       
        //initialize 3d scene
        api.init();
        
        //complete launch of ui
        Scene scene = new Scene(root);        
        
        //keyboard listener
        scene.setOnKeyPressed(e-> {
            RayDeviceInterface device = api.getDevice(RAYTRACE);
            switch(e.getCode())
            {
                case DIGIT1: 
                    device.setShadeType(NORMAL_SHADE);
                    device.resume();
                    break;
                case DIGIT2:
                    device.setShadeType(RAYTRACE_SHADE);
                    device.resume();
                    break;
            }
        });
        
        primaryStage.setScene(scene);
        primaryStage.setTitle("Mamba Tracer");
        primaryStage.setMinWidth(1250);
        primaryStage.setMinHeight(580);
        primaryStage.show();
        primaryStage.setOnCloseRequest(e -> {
            System.exit(0);
        });
    }
}
