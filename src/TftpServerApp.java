import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import network.TftpServer;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * client for TFTP
 */
public class TftpServerApp extends Application {
    /**
     * app window width
     */
    private static final double WINDOW_WIDTH = 600;

    /**
     * app window height
     */
    private static final double WINDOW_HEIGHT = 600;

    /**
     * the max count of the log textarea count
     */
    public static final int MAX_LOG_TEXTAREA_WORD_COUNT = 50000;


    private StringBuilder logBuffer = new StringBuilder();

    private volatile Timer logTimer;

    private volatile String logText = "";

    /**
     * use to log.
     */
    private TextArea logTextArea;

    /**
     * base folder text field.
     */
    private TextField baseFolderTextField;

    private Button chooseFolderBtn;

    private Stage stage;


    /**
     * the tftp server deal with the core of tftp.
     */
    private TftpServer tftpServer;


    @Override
    public void start(Stage stage) throws Exception {
        tftpServer = new TftpServer(this::printlnLogMsg);
        //when the window close, release the resources.
        stage.setOnCloseRequest(this::dispose);

        this.stage = stage;
        VBox root = new VBox();
        root.setSpacing(5);
        //add all children panes.
        root.getChildren().addAll(buildBaseFileConfigPane(), buildControlBtnPane(), buildLogPane());

        stage.setTitle("XXX's TFTP Server");
        stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
        stage.setResizable(false);

        stage.show();
    }

    /**
     * release the resources.
     */
    private void dispose(WindowEvent event){
        try{
            tftpServer.stop();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * build the pane of control button.
     * @return
     */
    private Pane buildControlBtnPane() {
        HBox pane = new HBox();
        pane.setSpacing(5);

        Button startBtn = new Button("Start");
        startBtn.setStyle( "-fx-background-color: green;");
        startBtn.setOnAction(event -> {
            if(tftpServer.isRunning()){
                tftpServer.stop();
                startBtn.setStyle( "-fx-background-color: green;");
                startBtn.setText("Start");
                chooseFolderBtn.setDisable(false);
            }else{
                tftpServer.start();
                startBtn.setStyle( "-fx-background-color: red;");
                startBtn.setText("Stop");
                chooseFolderBtn.setDisable(true);
            }
        });


        pane.getChildren().addAll(startBtn);
        return pane;
    }

    /**
     * build the base file dir config pane.
     * @return
     */
    private Pane buildBaseFileConfigPane() throws IOException {
        VBox pane = new VBox();
        pane.setSpacing(5);

        chooseFolderBtn = new Button("Choose Folder");
        chooseFolderBtn.setOnAction( event -> {
            //select base dir.
            File file = new DirectoryChooser().showDialog(stage);
            if (file == null) {
                return;
            }

            try {
                baseFolderTextField.setText(file.getCanonicalPath());
                tftpServer.setBaseDir(file.getCanonicalPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        //the base file dir
        baseFolderTextField = new TextField();
        baseFolderTextField.setEditable(false);
        baseFolderTextField.setFont(Font.font("MONOSPACED", FontWeight.NORMAL, baseFolderTextField.getFont().getSize()));
        //listener the text change to reset the column count.
        baseFolderTextField.textProperty().addListener(e ->{
            int count = Integer.max(20, baseFolderTextField.getText().length());
            baseFolderTextField.setPrefColumnCount(count);
        });

        //make scrollable
        ScrollPane sp = new ScrollPane();
        sp.setContent(baseFolderTextField);


        //initial the cur directory to display.
        String initpath = new File(".").getCanonicalPath();
        baseFolderTextField.setText(initpath);
        tftpServer.setBaseDir(initpath);

        pane.getChildren().addAll(chooseFolderBtn, sp);
        return pane;
    }

    /**
     * show the alert msg.
     * @param msg
     */
    private void showMsg(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Message");
        alert.setHeaderText("Message");
        alert.setContentText(msg);
        alert.showAndWait();
    }



    /**
     * build the log pane.
     *
     * @return log pane.
     */
    private Pane buildLogPane() {
        VBox pane = new VBox();
        pane.setSpacing(5);

        //the log tile.
        Label title = new Label("Log:");

        logTextArea = new TextArea();
        //fill the remain blank.
        logTextArea.setPrefWidth(WINDOW_WIDTH);
        logTextArea.setPrefHeight(WINDOW_HEIGHT);
        //if the line becomes too long to fit, the line is wrapped on a word boundary.
        logTextArea.setWrapText(true);

        pane.getChildren().addAll(title, logTextArea);
        return pane;
    }

    /**
     * prinltn the log msg to ui.
     * @param logMsg
     */
    private synchronized void printlnLogMsg(String logMsg){
        addLog(logMsg);
        //if many msg need to log at a short time, the application with be every slow,
        //so use the timer to after 0.2s update the log.
        if(logTimer == null){
            logTimer = new Timer("logTimer", true);
            logTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    updateLogTextArea();
                }
            }, 200);
        }
    }

    private synchronized void updateLogTextArea() {
        logText += getLogBufferStr();
        if(logText.length() > MAX_LOG_TEXTAREA_WORD_COUNT){
            logText = logText.substring(logText.length() - MAX_LOG_TEXTAREA_WORD_COUNT);
        }

        Platform.runLater(() ->{
            logTextArea.setText(logText);
            logTextArea.setScrollTop(Double.MAX_VALUE);
        });
        logTimer = null;
    }

    private synchronized void addLog(String logMsg){
        logBuffer.append(logMsg);
        logBuffer.append("\n");
    }

    private synchronized String getLogBufferStr(){
        String result = logBuffer.toString();
        logBuffer.setLength(0);
        return result;
    }

    public static void main(String[] args) {
        //start the app.
        launch(args);
    }
}
