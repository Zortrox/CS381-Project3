/**
 * Created by Zortrox on 3/23/2017.
 */

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;

import static javax.swing.JFileChooser.APPROVE_OPTION;

public class FileClient {

    public static void main(String[] args) {
        NetObject client = new NetObject("File Client");
    }
}
