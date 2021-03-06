package edu.oswego.cs;

import edu.oswego.cs.network.opcodes.PacketOpcode;
import edu.oswego.cs.network.opcodes.ParticipantOpcode;
import edu.oswego.cs.network.packets.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.sound.sampled.*;
import java.io.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SceneController1 {

    public Button listServer;
    public Button backButton;
    public Button RefreshButton;
    public Button JoinServerButton;
    private Parent root;
    private Stage stage;
    private Scene scene;
    public Label connectedToLabel = null;
    public Label chatroomLabel;

    public TextField ServerNameTextField;
    public TextField PasswordTextField;
    public Spinner<Integer> NumberOfParticipants;

    public static Listener listener;
    public static Thread speakerThread;
    public volatile static ParticipantACK participantACK;
    public volatile static ParticipantData participantData;

    public Button talkButton;
    public volatile boolean isTalking = false;

    @FXML
    public void switchToMainMenu(ActionEvent event) throws IOException {
        root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("MainMenu2.fxml")));
        stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        scene = new Scene(root);
        stage.setScene(scene);
        stage.show();
    }

    @FXML
    public void LeaveRoom(ActionEvent event) throws IOException {

        if (EncryptedVoiceChat.connectedToRoom) {

            EncryptedVoiceChat.connectedToRoom = false;
            if (speakerThread != null) {
                speakerThread.interrupt();
            }
            EncryptedVoiceChat.selectedRoom = "";

            ParticipantData participantData1 = new ParticipantData(ParticipantOpcode.LEAVE, EncryptedVoiceChat.port);
            EncryptedVoiceChat.socket.getOutputStream().write(participantData1.getBytes());
            EncryptedVoiceChat.socket.getOutputStream().flush();

            root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("MainMenu2.fxml")));
            stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            scene = new Scene(root);
            stage.setScene(scene);
            stage.show();
        }


    }

    @FXML
    public void switchCreateServer(ActionEvent event) throws IOException {
        root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("CreateServer.fxml")));
        stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        scene = new Scene(root);
        stage.setScene(scene);
        stage.show();
    }

    @FXML
    public void switchToServerList(ActionEvent event) throws IOException {
        if (listener != null) {
            listener.setSocketBroken(true);
//                listener.setSocket(null);
            listener.interrupt();
        }


        final int serverNameLimit = 12;

        root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("ListServer.fxml")));
        stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        ListView lv = (ListView) root.getChildrenUnmodifiable().get(0);

        Packet packet = null;
        while (packet == null) {
            ExecutorService executor = Executors.newWorkStealingPool();
            Callable<Packet> task = new Callable<Packet>() {
                public Packet call() throws IOException {
                    ParticipantData participantData = new ParticipantData(ParticipantOpcode.LIST_SERVERS, EncryptedVoiceChat.port);
                    EncryptedVoiceChat.socket.getOutputStream().write(participantData.getBytes());
                    byte[] buffer = new byte[1024];
                    EncryptedVoiceChat.socket.getInputStream().read(buffer);
                     return Packet.parse(buffer);
                }
            };


            Future<Packet> future = executor.submit(task);
            try {
                packet = future.get(100, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException ex) {
                if (participantACK != null) {
                    packet = participantACK;
                    break;
                }

            } catch (InterruptedException e) {
                System.out.println("Inter");
            } catch (ExecutionException e) {
                System.out.println("EXEc");
            } finally {
                future.cancel(true); // may or may not desire this
            }

        }


        if (packet instanceof ParticipantACK) {
            ParticipantACK participantACK = (ParticipantACK) packet;
            for (String param : participantACK.getParams()) {
                String name = param.split(";")[0];
                for (int i = serverNameLimit - name.length(); i > 0; i--)
                    name = name.concat("  ");
                if (name.length() % 2 != 0) name = name.concat("  ");
                String currentParticipants = param.split(";")[1].split("/")[0];
                String maxParticipants = param.split(";")[1].split("/")[1];
                Label serverNameLabel = new Label(name);
                serverNameLabel.setAlignment(Pos.CENTER_LEFT);
                Label participantsLabel = new Label("(" + currentParticipants + "/" + maxParticipants + ")");
                participantsLabel.setAlignment(Pos.CENTER_RIGHT);
                HBox hbox = new HBox(serverNameLabel, participantsLabel);
                hbox.setSpacing(150);
                hbox.setAlignment(Pos.CENTER);
//                hbox.setSpacing(180);
                lv.getItems().add(hbox);
                EncryptedVoiceChat.chatrooms.add(param);
            }
        } else if (packet instanceof ErrorPacket) {
            ErrorPacket errorPacket = (ErrorPacket) packet;
            ServerConnection.displayError(errorPacket.getErrorOpcode() + "; " + errorPacket.getErrorMsg());
        }


        HBox hBox = new HBox(root);
        scene = new Scene(hBox);
        stage.setScene(scene);
        stage.show();
    }

    @FXML
    public void listViewOnChange(Event event) {
        ListView<HBox> lv = (ListView<HBox>) event.getSource();
        HBox hbox = lv.getSelectionModel().getSelectedItem();
        EncryptedVoiceChat.selectedRoom = ((Label) hbox.getChildrenUnmodifiable().get(0)).getText().replace(" ", "");
    }

    @FXML
    public void switchToActiveChat(ActionEvent event) throws IOException {

        if (EncryptedVoiceChat.selectedRoom == null || EncryptedVoiceChat.selectedRoom.equals("")) return;

        root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("ListServer.fxml")));
        stage = (Stage) ((Node) event.getSource()).getScene().getWindow();

        //root.getChildrenUnmodifiable().get(0).addEventHandler();
        // figure out how to see what is selected in HBox soo that you can pick that item to be sent in packet.

         Packet p = null;
        while (p == null) {
            ExecutorService executor = Executors.newWorkStealingPool();
            Callable<Packet> task = new Callable<Packet>() {
                public Packet call() throws IOException {
                    ParticipantData participantData = new ParticipantData(ParticipantOpcode.JOIN, EncryptedVoiceChat.port, new String[]{EncryptedVoiceChat.selectedRoom});
                    EncryptedVoiceChat.socket.getOutputStream().write(participantData.getBytes());
                    byte[] buffer = new byte[1024];
                    EncryptedVoiceChat.socket.getInputStream().read(buffer);
                    return Packet.parse(buffer);
                }
            };

            Future<Packet> future = executor.submit(task);
            try {
                p = future.get(300, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException ex) {
                if (participantACK != null || participantData != null) {
                    p = participantACK;
                    break;
                }

            } catch (InterruptedException e) {
                System.out.println("Inter");
            } catch (ExecutionException e) {
                System.out.println("EXEc");
            } finally {
                future.cancel(true);
            }

        }

        if (p instanceof ErrorPacket) {
            System.out.println("Error: " + ((ErrorPacket) p).getErrorOpcode());
        } else if (p instanceof ParticipantACK) {
            EncryptedVoiceChat.connectedToRoom = true;
            root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("ActiveChat.fxml")));
            for (Node n : root.getChildrenUnmodifiable()) {
                if (n!=null) {
                    if (n.getId() != null && n.getId().equals("L")) {
                        ((Label) n).setText(EncryptedVoiceChat.selectedRoom);
                        ((Label) n).setAlignment(Pos.CENTER);
                    }
                }
            }
            stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            scene = new Scene(root);
            stage.setScene(scene);
            stage.show();

            listener = new Listener(EncryptedVoiceChat.socket);
            listener.start();

        }


    }


    @FXML
    public void createServer(ActionEvent event) throws IOException {
        String serverName = ServerNameTextField.getText();
        root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("CreateServer.fxml")));
        stage = (Stage) ((Node) event.getSource()).getScene().getWindow();

        if (serverName.length() > 12) {
            Label errorLabel = (Label) root.lookup("#errorLabel");
            errorLabel.setText("Error: Chat room > 12 char");
            scene = new Scene(root);
            stage.setScene(scene);
            stage.show();
        } else {
            String numberOfParticipants = String.valueOf(NumberOfParticipants.getValue());
            ParticipantData participantData = new ParticipantData(ParticipantOpcode.CREATE_SERVER, EncryptedVoiceChat.port, new String[]{serverName, numberOfParticipants});
            EncryptedVoiceChat.socket.getOutputStream().write(participantData.getBytes());

            participantData = new ParticipantData(ParticipantOpcode.JOIN, EncryptedVoiceChat.port, new String[]{serverName});
            EncryptedVoiceChat.socket.getOutputStream().write(participantData.getBytes());
            EncryptedVoiceChat.connectedToRoom = true;

            // error check

            root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("ActiveChat.fxml")));
            for (Node n : root.getChildrenUnmodifiable()) {
                if (n!=null) {
                    if (n.getId() != null && n.getId().equals("L")) {
                        ((Label) n).setText(serverName);
                        ((Label) n).setAlignment(Pos.CENTER);
                    }
                }
            }
            stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            scene = new Scene(root);
            stage.setScene(scene);
            stage.show();
        }
        //update stage when we get new person to join room


    }

    @FXML
    public void talkButton(ActionEvent event) throws InterruptedException {

        Task<Void> talk = new Task<Void>() {

            @Override
            protected Void call() throws Exception {
                AudioCapture audioCapture = new AudioCapture();
                new Thread(audioCapture::startCapture).start();
                try {
                    talkButton.setText("Talking");
                    Thread.sleep(5000);
                    audioCapture.stopCapture();

                    FileInputStream fileIn = new FileInputStream("audio.wav");
                    SoundPacket soundPacket = new SoundPacket(PacketOpcode.SRQ, EncryptedVoiceChat.port);
                    EncryptedVoiceChat.socket.getOutputStream().write(soundPacket.getBytes());
                    Thread.sleep(1000);
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(EncryptedVoiceChat.socket.getOutputStream());
                    objectOutputStream.writeObject(fileIn.readAllBytes());

                } catch (Exception e) {e.printStackTrace();}
                talkButton.setText("Talk");
                return null;
            }
        };
        talk.run();
        //talk.messageProperty().addListener((obs, oldMessage, newMessage) -> talkButton.setText(newMessage));


    }
}
