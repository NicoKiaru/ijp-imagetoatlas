package ch.epfl.biop;

import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import ch.epfl.biop.atlas.allen.adultmousebrain.AllenBrainAdultMouseAtlasCCF2017;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ij.Prefs;
import sc.fiji.bdvpg.bdv.config.BdvSettingsGUISetter;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.net.URL;

public class ABBAHelper {

    public static void displayABBALogo(int ms) {
        JFrame frameStart = new JFrame();
        frameStart.setUndecorated(true);
        frameStart.setBackground(new Color(1.0f, 1.0f, 1.0f, 0.5f));
        frameStart.setPreferredSize(new Dimension(960,540));
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] gd = ge.getScreenDevices();
        frameStart.setLocation(gd[0].getDefaultConfiguration().getBounds().width/2-960/2, gd[0].getDefaultConfiguration().getBounds().height/2-540/2);
        frameStart.pack();
        URL openImage = MultiSlicePositioner.class.getResource("/graphics/ABBA.png");
        try {
            BufferedImage myPicture = ImageIO.read(openImage);
            frameStart.add(new JLabel(new ImageIcon(myPicture)));
            frameStart.setVisible(true);
            Thread.sleep(ms);
            frameStart.setVisible(false);
            frameStart.dispose();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String keyPrefix = AllenBrainAdultMouseAtlasCCF2017.class.getName()+".";

    public static String getMapUrl() {
        ABBASettings settings = getFromLocalFiji();
        if (settings==null) {
            return Prefs.get(keyPrefix+"mapUrl","");
        } else {
            return settings.pathToABBAAtlas;
        }
    }

    public static String getOntologyUrl() {
        ABBASettings settings = getFromLocalFiji();
        if (settings==null) {
            return Prefs.get(keyPrefix+"ontologyUrl","");
        } else {
            return settings.pathToABBAOntology;
        }
    }

    public static File getElastixExeFile() {
        ABBASettings settings = getFromLocalFiji();
        if (settings==null) {
            return null;
        } else {
            return new File(settings.pathToElastixExeFile);
        }
    }

    public static File getTransformixExeFile() {
        ABBASettings settings = getFromLocalFiji();
        if (settings==null) {
            return null;
        } else {
            return new File(settings.pathToTransformixExeFile);
        }
    }

    public static class ABBASettings {
        public String pathToABBAAtlas;
        public String pathToABBAOntology;
        public String pathToElastixExeFile;
        public String pathToTransformixExeFile;
    }

    static public ABBASettings getFromLocalFiji() {
        File abbasettings = new File("plugins"+File.separator+"abbasettings.txt");
        if (abbasettings.exists()) {
            Gson gson = new Gson();
            try {
                return gson.fromJson(new FileReader(abbasettings.getAbsoluteFile()), ABBASettings.class);
            } catch (FileNotFoundException e) {
                return null;
            }
        } else return null;
    }

    static public void setToLocalFiji(ABBASettings settings) {
        File abbaSettingsFile = new File("plugins"+File.separator+"abbasettings.txt");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String abbaSettings = gson.toJson(settings);
        try {
            PrintWriter out = new PrintWriter(abbaSettingsFile);
            out.println(abbaSettings);
            out.close();
        } catch (FileNotFoundException e) {
            System.err.println("Could not print abba settings file "+abbaSettingsFile.getAbsolutePath());
            e.printStackTrace();
        }
    }

}
