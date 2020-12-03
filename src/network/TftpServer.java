package network;

import network.packet.*;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * the server deal with the core of tftp.
 */
public class TftpServer {

    /**
     * the port for receive request.
     */
    private static final int PORT = 69;

    /**
     * buffer max size.
     */
    private static final int BUFFER_MAX_LENGTH = 1024;

    /**
     * the time out of handler receive.
     */
    private static final int HANDLER_RECEIVE_TIMEOUT = 5000;

    /**
     * the retry time of when meet the error data
     */
    private static final int RETRY_TIME = 4;

    /**
     * use to log info.
     */
    private Consumer<String> logger;

    private DatagramSocket reqSocket;

    /**
     * the status of server.
     */
    private volatile boolean run;

    /**
     * the threadpool to deal request and response.
     */
    private ExecutorService threadpool;

    /**
     * the dir for save file.
     */
    private String baseDir;

    public TftpServer(Consumer<String> logger){
        this.logger = logger;
    }

    /**
     * receive the request from client.
     */
    private void receiveRequest(){
        try {
            reqSocket = new DatagramSocket(PORT);

            while (run){
                byte[] buffer = new byte[BUFFER_MAX_LENGTH];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                reqSocket.receive(packet);
                TftpPacket tftpPacket = TftpPacketFactory.buildTftpPacket(packet);
                short opCode = tftpPacket.getOpCode();
                logByListenerThread("Receive a packet, opcode:" + opCode);
                //upload
                if(opCode == TftpPacketConsts.OP_WRQ){
                    doUpload(tftpPacket);
                }else if(opCode == TftpPacketConsts.OP_RRQ){
                    doDownload(tftpPacket);
                }else{
                    sendAndLogErrPacket(reqSocket, tftpPacket, TftpPacketConsts.ERRCODE_ILLOP, "Illegal Opcode.");
                }

            }

        } catch (Exception e) {
            //if the server is stop, then do not log.
            if(run){
                e.printStackTrace();
                logByListenerThread("Receive request error:" + e.getMessage());
            }
        }

    }

    /**
     * deal download.
     * @param reqPacket
     */
    private void doDownload(TftpPacket reqPacket) {
        threadpool.execute(()->{
            try (DatagramSocket socket = new DatagramSocket()) {
                logByHandlerThread(String.format("Download-Create another socket on port:<%s>", socket.getLocalPort()));
                socket.setSoTimeout(HANDLER_RECEIVE_TIMEOUT);

                WRRQPacket wrrqPacket = (WRRQPacket)reqPacket;
                String fileName = wrrqPacket.getFileName();
                String mode = wrrqPacket.getMode();
                logByHandlerThread(String.format("Download-WRQ-Receive:fileName<%s> mode<%s>", fileName, mode));
                if(!TftpPacketConsts.MODE_OCTET.equals(mode)){
                    //only allow OCTET
                    sendAndLogErrPacket(socket, reqPacket, TftpPacketConsts.ERRCODE_UNDEF, "Only allow octet.");
                    return;
                }


                String targetPath = this.baseDir + File.separator + fileName;
                logByHandlerThread(String.format("Download-Open file:%s", targetPath));
                File file = new File(targetPath);
                if(!file.exists()){
                    //File not found
                    sendAndLogErrPacket(socket, reqPacket, TftpPacketConsts.ERRCODE_NOTFD, "File not found.");
                    return;
                }

                byte[] buffer = new byte[BUFFER_MAX_LENGTH];
                DatagramPacket responseDatagramPacket = new DatagramPacket(buffer, buffer.length);

                try(BufferedInputStream bi = new BufferedInputStream(new FileInputStream(file))){
                    DATAPacket dataPacket = TftpPacketFactory.buildDatapacket(reqPacket);
                    //start read the data from file.
                    BooleanSupplier dataReader = dataPacket.readBlockData(bi);
                    while(dataReader.getAsBoolean() && run){
                        int retryCount = 0;
                        while(true){
                            //send data to client.
                            logByHandlerThread(String.format("Download-DATA-Send:block<%s>", dataPacket.getBlockNum()));
                            socket.send(dataPacket.build());
                            //getResponse.
                            try{
                                while (true){
                                    socket.receive(responseDatagramPacket);

                                    TftpPacket response = TftpPacketFactory.buildTftpPacket(responseDatagramPacket);
                                    if(response.getOpCode() != TftpPacketConsts.OP_ACK){
                                        logByHandlerThread(String.format("Download-opcode error, cur:%s, expect:%s", response.getOpCode(), TftpPacketConsts.OP_ACK));
                                        logByHandlerThread("Ignore err packet...");
                                        continue;
                                    }

                                    short responseBlockNo = ((ACKPacket) response).getBlockNo();
                                    logByHandlerThread(String.format("Download-ACK-Receive:blockNo<%s>", responseBlockNo));
                                    if(responseBlockNo != dataPacket.getBlockNum()){
                                        logByHandlerThread(String.format("Download-ACK error, cur:%s, expect:%s", responseBlockNo, dataPacket.getBlockNum()));
                                        logByHandlerThread("Ignore err packet...");
                                        continue;
                                    }

                                    break;
                                }

                            } catch (SocketTimeoutException e) {
                                e.printStackTrace();
                                retryCount = checkCanRetryBusinessErr(retryCount,"Download-Receive:timeout");
                                continue;
                            }

                            //if no error happen, then do next
                            break;
                        }
                    }
                    logByHandlerThread("Download-Finish");

                } catch (IOException e) {
                    e.printStackTrace();
                    sendAndLogErrPacket(socket, reqPacket, TftpPacketConsts.ERRCODE_ACCESS, "File open error.");
                    throw new RuntimeException(e.getMessage());
                }
            } catch (Exception e) {
                if(run){
                    e.printStackTrace();
                    logByHandlerThread("Download-Error:" + e.getMessage());
                }
            }
        });
    }

    /**
     * Listener thread log
     * @param msg
     */
    private void logByListenerThread(String msg){
        logWithThreadInfo("ListenerThread", msg);
    }

    /**
     * handler thread log
     * @param msg
     */
    private void logByHandlerThread(String msg){
        logWithThreadInfo("HandlerThread", msg);
    }

    /**
     * log msg with thread info
     * @param threadName
     * @param msg
     */
    private void logWithThreadInfo(String threadName, String msg){
        logger.accept(String.format("%s(%s): %s", threadName, Thread.currentThread().getName(), msg));
    }

    /**
     * deal upload.
     * @param reqPacket
     */
    private void doUpload(TftpPacket reqPacket) {
        threadpool.execute(()->{
            try (DatagramSocket socket = new DatagramSocket()) {
                logByHandlerThread(String.format("Upload-Create another socket on port:<%s>", socket.getLocalPort()));
                socket.setSoTimeout(HANDLER_RECEIVE_TIMEOUT);

                WRRQPacket wrrqPacket = (WRRQPacket)reqPacket;
                String fileName = wrrqPacket.getFileName();
                String mode = wrrqPacket.getMode();
                logByHandlerThread(String.format("Upload-WRQ-Receive:fileName<%s> mode<%s>", fileName, mode));
                if(!TftpPacketConsts.MODE_OCTET.equals(mode)){
                    //only allow OCTET
                    sendAndLogErrPacket(socket, reqPacket, TftpPacketConsts.ERRCODE_UNDEF, "Only allow octet.");
                    return;
                }

                //save the file data.
                String savePath = this.baseDir + File.separator + fileName;
                logByHandlerThread(String.format("Upload-Start save file:%s", savePath));


                byte[] buffer = new byte[BUFFER_MAX_LENGTH];
                DatagramPacket responseDatagramPacket = new DatagramPacket(buffer, buffer.length);

                File saveFile = new File(savePath);
                try(BufferedOutputStream bo = new BufferedOutputStream(new FileOutputStream(saveFile))){
                    short blockNum = 1;
                    ACKPacket ackPacket = TftpPacketFactory.buildACKPacket(wrrqPacket, (short) 0);

                    boolean isFinish = false;

                    int retryCounter = 0;
                    while (run){
                        //send ack to client
                        logByHandlerThread(String.format("Upload-ACK-Send:blockNo<%s>", ackPacket.getBlockNo()));
                        socket.send(ackPacket.build());
                        if(isFinish){
                            break;
                        }


                        try{
                            while(run){
                                //receive file data form client.
                                socket.receive(responseDatagramPacket);
                                TftpPacket reponsePacket = TftpPacketFactory.buildTftpPacket(responseDatagramPacket);
                                //op err.
                                if(reponsePacket.getOpCode() != TftpPacketConsts.OP_DATA){
                                    logByHandlerThread(String.format("Upload-opcode error, cur:%s, expect:%s", reponsePacket.getOpCode(), TftpPacketConsts.OP_DATA));
                                    logByHandlerThread("Ignore err packet...");
                                    continue;
                                }

                                DATAPacket dataPacket = (DATAPacket)reponsePacket;
                                logByHandlerThread(String.format("Upload-DATA-Receive:blockNo<%s>", dataPacket.getBlockNum()));
                                if(blockNum != dataPacket.getBlockNum()){
                                    logByHandlerThread(String.format("Upload-blockNo error, cur:%s, expect:%s", dataPacket.getBlockNum(), blockNum));
                                    logByHandlerThread("Ignore err packet...");
                                    continue;
                                }

                                //save data to file.
                                dataPacket.writeBlockData(bo);
                                if(dataPacket.isLast()){
                                    isFinish = true;
                                }

                                break;
                            }
                        }catch (SocketTimeoutException e){
                            retryCounter = checkCanRetryBusinessErr(retryCounter,"Upload-Receive:timeout");
                            continue;
                        }

                        retryCounter = 0;
                        //reset the block number.
                        ackPacket.resetBlockNo(blockNum);

                        ++blockNum;
                        if(blockNum >= Short.MAX_VALUE){
                            blockNum = 1;
                        }

                    }

                    bo.flush();
                    logByHandlerThread("Upload-Finish");
                } catch (IOException e) {
                    e.printStackTrace();
                    sendAndLogErrPacket(socket, reqPacket, TftpPacketConsts.ERRCODE_ACCESS, "File open error.");
                    throw new RuntimeException(e.getMessage());
                }
            } catch (Exception e) {
                if(run){
                    e.printStackTrace();
                    logByHandlerThread("Upload-Error:" + e.getMessage());
                }
            }
        });

    }

    /**
     * check retry count and log.
     * @param retryCounter
     * @param errMsg
     * @return
     * @throws IOException
     */
    private int checkCanRetryBusinessErr(int retryCounter, String errMsg) throws IOException {
        if(retryCounter >= RETRY_TIME){
            throw new RuntimeException(errMsg);
        }

        logByHandlerThread(errMsg);
        ++retryCounter;
        logByHandlerThread(String.format("Retrying:retry count:%s...", retryCounter));
        return retryCounter;
    }

    private ERRORPacket sendAndLogErrPacket(DatagramSocket socket, TftpPacket reponsePacket, short errCode, String errMsg) throws IOException {
        ERRORPacket errPacket = TftpPacketFactory.buildERRORpacket(reponsePacket, errCode, errMsg);
        logByHandlerThread(String.format("ERROR-Send:ErrCode<%s> ErrMsg<%s>", errPacket.getErrCode(), errPacket.getErrMsg()));
        socket.send(errPacket.build());
        return errPacket;
    }


    /**
     * start the server.
     */
    public void start(){
        run = true;
        threadpool = Executors.newCachedThreadPool();
        //start receive the request.
        threadpool.execute(this::receiveRequest);
        logger.accept("Server started");
    }

    /**
     * get the server status.
     * @return
     */
    public boolean isRunning(){
        return run;
    }

    /**
     * stop the server
     */
    public void stop(){
        run = false;

        //stop the threadpool.
        if(threadpool != null){
            try{
                threadpool.shutdownNow();
                threadpool = null;
            }catch (Exception e){
                e.printStackTrace();
                logger.accept("Stop error:" + e.getMessage());
            }
        }

        //close the reqsocket.
        if(reqSocket != null){
            try{
                reqSocket.close();
                reqSocket = null;
            }catch (Exception e){
                e.printStackTrace();
                logger.accept("Stop error:" + e.getMessage());
            }
        }

        logger.accept("Server stop");
    }

    /**
     * set the base dir.
     * @param baseDir
     */
    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

}
