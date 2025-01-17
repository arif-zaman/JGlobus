package transfer_protocol.module;

import client.AdaptiveGridFTPClient;
import client.ConfigurationParams;
import client.FileCluster;
import client.hysterisis.Hysteresis;
import client.utils.HostResolution;
import client.utils.TunableParameters;
import client.utils.Utils;
import com.google.common.collect.Lists;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.globus.ftp.DataChannelAuthentication;
import org.globus.ftp.HostPort;
import org.globus.ftp.HostPortList;
import org.gridforum.jgss.ExtendedGSSCredential;
import org.gridforum.jgss.ExtendedGSSManager;
import org.ietf.jgss.GSSCredential;
import transfer_protocol.util.XferList;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;

import static client.utils.Utils.getChannels;

public class GridFTPClient implements Runnable {
    public static FTPClient ftpClient;
    public static ExecutorService executor;
    public static Queue<InetAddress> sourceIpList, destinationIpList;




    static int fastChunkId = -1, slowChunkId = -1, period = 0;
    URI usu = null, udu = null;
    static FTPURI su = null, du = null;

    Thread connectionThread, transferMonitorThread;
    HostResolution sourceHostResolutionThread, destinationHostResolutionThread;
    GSSCredential srcCred =null, dstCred =null, cred = null;

    volatile int rv = -1;
    static int perfFreq = 1;
    public boolean useDynamicScheduling = false;
    public boolean useOnlineTuning =  false;

    private static final Log LOG = LogFactory.getLog(GridFTPClient.class);

    public GridFTPClient(String source, String dest) {
        try {
            usu = new URI(source).normalize();
            udu = new URI(dest).normalize();
        } catch (URISyntaxException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        executor = Executors.newFixedThreadPool(30);
    }

    public void setPerfFreq (int perfFreq) {
        this.perfFreq = perfFreq;
    }

    public static boolean setupChannelConf(ChannelModule.ChannelPair channelPair,
                                           int channelId,
                                           FileCluster chunk,
                                           XferList.MlsxEntry firstFileToTransfer) {
        TunableParameters params = chunk.getTunableParameters();
        channelPair.chunk = chunk;
        try {
            channelPair.setID(channelId);
            if (params.getParallelism() > 1)
                channelPair.setParallelism(params.getParallelism());
            channelPair.setPipelining(params.getPipelining());
            channelPair.setBufferSize(params.getBufferSize());
            channelPair.setPerfFreq(perfFreq);
            channelPair.setDataChannelAuthentication(DataChannelAuthentication.NONE);
            if (!channelPair.isDataChannelReady()) {
                // Use extended mode to be able to reuse the channel for multi-file transfers
                channelPair.setTypeAndMode('I', 'E');
                if (channelPair.isStripingEnabled()) {
                    HostPortList hpl = channelPair.setStripedPassive();
                    channelPair.setStripedActive(hpl);
                } else {
                    HostPort hp = channelPair.setPassive();
                    channelPair.setActive(hp);
                }
            }
            channelPair.pipeTransfer(firstFileToTransfer);
            channelPair.inTransitFiles.add(firstFileToTransfer);
        } catch (Exception ex) {
            System.out.println("Failed to setup channel");
            ex.printStackTrace();
            return false;
        }
        return true;
    }

    public void process() throws Exception {

        // Check if we were provided a proxy. If so, load it.
        if (usu.getScheme().compareTo("gsiftp") == 0) {
            if (ConfigurationParams.proxyFile != null) {
                cred = readCredential(ConfigurationParams.proxyFile);
                srcCred = dstCred = cred;
            }
            if (ConfigurationParams.srcCred != null) {
                srcCred = readCredential(ConfigurationParams.srcCred);
            }
            if (ConfigurationParams.dstCred != null) {
                dstCred = readCredential(ConfigurationParams.dstCred);
            }
        }

        // Attempt to connect to hosts.
        // TODO: Differentiate between temporary errors and fatal errors.
        try {
            su = new FTPURI(usu, srcCred);
            du = new FTPURI(udu, dstCred);
        } catch (Exception e) {
            fatal("couldn't connect to server: " + e.getMessage());
        }
        // Attempt to connect to hosts.
        // TODO: Differentiate between temporary errors and fatal errors.
        try {
            ftpClient = new FTPClient(su, du);
        } catch (Exception e) {
            e.printStackTrace();
            fatal("error connecting: " + e);
        }
        // Check that src and dest match.
        if (su.path.endsWith("/") && du.path.compareTo("/dev/null") == 0) {  //File to memory transfer

        } else if (su.path.endsWith("/") && !du.path.endsWith("/")) {
            fatal("src is a directory, but dest is not");
        }
        ftpClient.fileClusters = new LinkedList<>();
    }

    GSSCredential readCredential (String credPath) throws Exception {
        GSSCredential gssCredential = null;
        try {
            File cred_file = new File(credPath);
            FileInputStream fis = new FileInputStream(cred_file);
            byte[] cred_bytes = new byte[(int) cred_file.length()];
            fis.read(cred_bytes);
            System.out.println("Setting parameters");
            //GSSManager manager = ExtendedGSSManager.getInstance();
            ExtendedGSSManager gm = (ExtendedGSSManager) ExtendedGSSManager.getInstance();
            gssCredential = gm.createCredential(cred_bytes,
                    ExtendedGSSCredential.IMPEXP_OPAQUE,
                    GSSCredential.DEFAULT_LIFETIME, null,
                    GSSCredential.INITIATE_AND_ACCEPT);
            fis.close();
        } catch (Exception e) {
            fatal("error loading x509 proxy: " + e.getMessage());
        }
        return gssCredential;
    }

    private void abort() {
        if (ftpClient != null) {
            try {
                ftpClient.abort();
            } catch (Exception e) {
            }
        }

        close();
    }

    private void close() {
        try {
            for (ChannelModule.ChannelPair channelPair : ftpClient.channelList) {
                channelPair.close();
            }
        } catch (Exception e) {
        }
    }

    public void run() {
        try {
            process();
            rv = 0;
        } catch (Exception e) {
            LOG.warn("Client could not be establieshed. Exiting...");
            e.printStackTrace();
            System.exit(-1);
        }

    }

    public void fatal(String m) throws Exception {
        rv = 255;
        throw new Exception(m);
    }

    public void error(String m) throws Exception {
        rv = 1;
        throw new Exception(m);
    }

    public void start() {
        connectionThread = new Thread(this);
        connectionThread.start();
        // Check if there are multiple hosts behind given hostname
        sourceHostResolutionThread = new HostResolution(usu.getHost());
        destinationHostResolutionThread = new HostResolution(udu.getHost());
        sourceHostResolutionThread.start();
        destinationHostResolutionThread.start();
    }

    public void stop() {
        abort();
        //sink.close();
        close();
    }

    public int waitFor() {
        if (connectionThread != null) {
            try {
                connectionThread.join();
            } catch (Exception e) {
            }
        }
        // Make sure hostname resolution operations are completed before starting to a transfer
        try {
            sourceHostResolutionThread.join();
            destinationHostResolutionThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        sourceIpList = new LinkedList<>();
        destinationIpList = new LinkedList<>();
        for (InetAddress inetAddress : sourceHostResolutionThread.getAllIPs()) {
            if (inetAddress != null)
                sourceIpList.add(inetAddress);
        }
        for (InetAddress inetAddress : destinationHostResolutionThread.getAllIPs()) {
            if (inetAddress != null )
                destinationIpList.add(inetAddress);
        }

        return (rv >= 0) ? rv : 255;
    }

    public XferList getListofFiles() throws Exception {
        return ftpClient.getListofFiles(usu.getPath(), udu.getPath());
    }

    public void runTransfer(final FileCluster fileCluster) {
        XferList fileList = fileCluster.getRecords();
        TunableParameters tunableParameters = fileCluster.getTunableParameters();
        LOG.info("Transferring chunk " + fileCluster.getDensity().name() +
                " params:" + tunableParameters.toString() + " " + tunableParameters.getBufferSize() +
                " file count:" + fileList.count() +
                " size:" + (fileList.size() / (1024.0 * 1024)));

        ftpClient.fileClusters.add(fileCluster);
        int concurrency = tunableParameters.getConcurrency();

        fileList.channels = new LinkedList<>();
        fileList.initialSize = fileList.size();

        // Reserve one file for each channel, otherwise pipelining
        // may lead to assigning all files to one channel
        List<XferList.MlsxEntry> firstFilesToSend = Lists.newArrayListWithCapacity(concurrency);
        for (int i = 0; i < concurrency; i++) {
            firstFilesToSend.add(fileList.pop());
        }

        // Create <concurrency> times channels and start them
        for (int i = 0; i < concurrency; i++) {
            XferList.MlsxEntry firstFile = synchronizedPop(firstFilesToSend);
            Runnable transferChannel = new TransferChannel(fileCluster, i, firstFile);
            executor.submit(transferChannel);
        }

        // If not all of the files in firstFilsToSend list is used for any reason,
        // move files back to original xferlist xl.
        if (!firstFilesToSend.isEmpty()) {
            LOG.info("firstFilesToSend list has still " + firstFilesToSend.size() + "files!");
            synchronized (this) {
                for (XferList.MlsxEntry e : firstFilesToSend)
                    fileList.addEntry(e);
            }
        }
    }

    public XferList.MlsxEntry synchronizedPop(List<XferList.MlsxEntry> fileList) {
        synchronized (fileList) {
            return fileList.remove(0);
        }
    }

    public void waitForTransferCompletion() {
        // Check if all the files in all chunks are transferred
        for (FileCluster fileCluster: ftpClient.fileClusters)
            try {
                while (fileCluster.getRecords().totalTransferredSize < fileCluster.getRecords().initialSize) {
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        //Close all channels before exiting
        for (int i = 1; i < ftpClient.channelList.size(); i++) {
            ftpClient.channelList.get(i).close();
        }
        ftpClient.channelList.clear();
    }

    public static class TransferChannel implements Runnable {
        final int doStriping;
        int channelId;
        XferList.MlsxEntry firstFileToTransfer;
        FileCluster fileCluster;
        //List<String> blacklistedHosts

        public TransferChannel(FileCluster fileCluster, int channelId, XferList.MlsxEntry file) {
            this.channelId = channelId;
            this.doStriping = 0;
            this.fileCluster = fileCluster;
            firstFileToTransfer = file;
        }

        @Override
        public void run() {
            boolean success = false;
            int trial = 0;
            while (!success && trial < 3) {
                try {
                    // Channel zero is main channel and already created
                    ChannelModule.ChannelPair channel;
                    InetAddress srcIp, dstIp;
                    // Distribute channels to available transfer nodes to balance load on them
                    synchronized (sourceIpList) {
                        srcIp = sourceIpList.poll();
                        sourceIpList.add(srcIp);
                    }
                    synchronized (destinationIpList) {
                        dstIp = destinationIpList.poll();
                        destinationIpList.add(dstIp);
                    }
                    //long start = System.currentTimeMillis();
                    URI srcUri = null, dstUri = null;

                    try {
                        srcUri = new URI(su.uri.getScheme(), su.uri.getUserInfo(), srcIp.getHostAddress(),
                                su.uri.getPort(), su.uri.getPath(), su.uri.getQuery(), su.uri.getFragment());
                        dstUri = new URI(du.uri.getScheme(), du.uri.getUserInfo(), dstIp.getHostAddress(),
                                du.uri.getPort(), du.uri.getPath(), du.uri.getQuery(), du.uri.getFragment());
                    } catch (URISyntaxException e) {
                        LOG.error("Updating URI host failed:", e);
                        System.exit(-1);
                    }
                    FTPURI srcFTPUri = new FTPURI(srcUri, su.cred);
                    FTPURI dstFTPUri = new FTPURI(dstUri, du.cred);
                    channel = new ChannelModule.ChannelPair(srcFTPUri, dstFTPUri);
                    success = setupChannelConf(channel, channelId, fileCluster, firstFileToTransfer);
                    if (success) {
                        synchronized (fileCluster.getRecords().channels) {
                            fileCluster.getRecords().channels.add(channel);
                        }
                        synchronized (ftpClient.channelList) {
                            ftpClient.channelList.add(channel);
                        }
                        ftpClient.transferList(channel);
                    } else {
                        trial++;

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // if channel is not established, then put the file back into the list
            if (!success) {
                synchronized (fileCluster.getRecords()) {
                    fileCluster.getRecords().addEntry(firstFileToTransfer);
                }
            }
        }

    }



    /*
    public void runMultiChunkTransfer(List<FileCluster> chunks, int[] channelAllocations) throws Exception {
        int totalChannels = 0;
        for (int channelAllocation : channelAllocations)
            totalChannels += channelAllocation;
        int totalChunks = chunks.size();

        long totalDataSize = 0;
        for (int i = 0; i < totalChunks; i++) {
            XferList xl = chunks.get(i).getRecords();
            totalDataSize += xl.size();
            xl.initialSize = xl.size();
            xl.channels = Lists.newArrayListWithCapacity(channelAllocations[i]);
            chunks.get(i).isReadyToTransfer = true;
            ftpClient.fileClusters.add(chunks.get(i));
        }

        // Reserve one file for each chunk before initiating channels otherwise
        // pipelining may cause assigning all fileClusters to one channel.
        List<List<XferList.MlsxEntry>> firstFilesToSend = new ArrayList<List<XferList.MlsxEntry>>();
        for (int i = 0; i < totalChunks; i++) {
            List<XferList.MlsxEntry> files = Lists.newArrayListWithCapacity(channelAllocations[i]);
            //setup channels for each chunk
            XferList xl = chunks.get(i).getRecords();
            for (int j = 0; j < channelAllocations[i]; j++) {
                files.add(xl.pop());
            }
            firstFilesToSend.add(files);
        }
        ftpClient.channelList = new ArrayList<>(totalChannels);
        int currentChannelId = 0;
        long start = System.currentTimeMillis();
        for (int i = 0; i < totalChunks; i++) {
            LOG.info(channelAllocations[i] + " channels will be created for chunk " + i);
            for (int j = 0; j < channelAllocations[i]; j++) {
                XferList.MlsxEntry firstFile = synchronizedPop(firstFilesToSend.get(i));
                Runnable transferChannel = new TransferChannel(chunks.get(i), currentChannelId, firstFile);
                currentChannelId++;
                futures.add(executor.submit(transferChannel));
            }
        }
        LOG.info("Created "  + ftpClient.channelList.size() + "channels");
        //this is monitoring connectionThread which measures throughput of each chunk in every 3 seconds
        //executor.submit(new TransferMonitor());
        for (Future<?> future : futures) {
            future.get();
        }

        long finish = System.currentTimeMillis();
        double thr = totalDataSize * 8 / ((finish - start) / 1000.0);
        LOG.info(" Time:" + ((finish - start) / 1000.0) + " sec Thr:" + (thr / (1000 * 1000)));
        // Close channels
        futures.clear();
        ftpClient.ccs.forEach(cp -> cp.close());
        ftpClient.ccs.clear();
    }
    */

    private void initializeMonitoring() {
        for (FileCluster fileCluster : ftpClient.fileClusters) {
            if (fileCluster.isReadyToTransfer) {
                XferList fileList = fileCluster.getRecords();
                LOG.info("Chunk:" + fileCluster.getDensity().name() +
                        " count:" + fileList.count() +
                        " size:" + Utils.printSize(fileList.size(), true) +
                        " parameters: " + fileCluster.getTunableParameters().toString());
                fileList.instantTransferredSize = fileList.totalTransferredSize;
            }
        }
    }

    public void startTransferMonitor() {
        if (transferMonitorThread == null || !transferMonitorThread.isAlive()) {
            transferMonitorThread = new Thread(new TransferMonitor());
            transferMonitorThread.start();
        }
    }

    private void monitorChannels(int interval, Writer writer, int timer) throws IOException {
        DecimalFormat df = new DecimalFormat("###.##");
        double[] estimatedCompletionTimes = new double[ftpClient.fileClusters.size()];
        for (int i = 0; i < ftpClient.fileClusters.size(); i++) {
            double estimatedCompletionTime = -1;
            FileCluster chunk = ftpClient.fileClusters.get(i);
            XferList xl = chunk.getRecords();
            double throughputInMbps = 8 * (xl.totalTransferredSize - xl.instantTransferredSize) / (xl.interval + interval);

            if (throughputInMbps == 0) {
                if (xl.totalTransferredSize == xl.initialSize) { // This chunk has finished
                    xl.weighted_throughput = 0;
                } else if (xl.weighted_throughput != 0) { // This chunk is running but current file has not been transferred
                    //xl.instant_throughput = 0;
                    estimatedCompletionTime = ((xl.initialSize - xl.totalTransferredSize) / xl.weighted_throughput) - xl.interval;
                    xl.interval += interval;
                    System.out.println("Chunk " + i +
                            "\t threads:" + xl.channels.size() +
                            "\t count:" + xl.count() +
                            "\t total:" + Utils.printSize(xl.size(), true) +
                            "\t interval:" + xl.interval +
                            "\t onAir:" + xl.onAir);
                } else { // This chunk is active but has not transferred any data yet
                    System.out.println("Chunk " + i +
                            "\t threads:" + xl.channels.size() +
                            "\t count:" + xl.count() +
                            "\t total:" + Utils.printSize(xl.size(), true)
                            + "\t onAir:" + xl.onAir);
                    if (xl.channels.size() == 0) {
                        estimatedCompletionTime = Double.POSITIVE_INFINITY;
                    } else {
                        xl.interval += interval;
                    }
                }
            } else {
                xl.instant_throughput = throughputInMbps;
                xl.interval = 0;
                if (xl.weighted_throughput == 0) {
                    xl.weighted_throughput = throughputInMbps;
                } else {
                    xl.weighted_throughput = xl.weighted_throughput * 0.6 + xl.instant_throughput * 0.4;
                }

                if (useOnlineTuning) {
                    ModellingThread.jobQueue.add(new ModellingThread.ModellingJob(
                            chunk, chunk.getTunableParameters(), xl.instant_throughput));
                }
                estimatedCompletionTime = 8 * (xl.initialSize - xl.totalTransferredSize) / xl.weighted_throughput;
                xl.estimatedFinishTime = estimatedCompletionTime;
                System.out.println("Chunk " + i +
                        "\t threads:" + xl.channels.size() +
                        "\t count:" + xl.count() +
                        "\t transferred:" + Utils.printSize(xl.totalTransferredSize, true) +
                        "/" + Utils.printSize(xl.initialSize, true) +
                        "\t throughput:" +  Utils.printSize(xl.instant_throughput, false) +
                        "/" + Utils.printSize(xl.weighted_throughput, true) +
                        "\testimated time:" + df.format(estimatedCompletionTime) +
                        "\t onAir:" + xl.onAir);
                xl.instantTransferredSize = xl.totalTransferredSize;
            }
            estimatedCompletionTimes[i] = estimatedCompletionTime;
            writer.write(timer + "\t" + xl.channels.size() + "\t" + (throughputInMbps)/(1000*1000.0) + "\n");
            writer.flush();
        }
        System.out.println("*******************");
        if (ftpClient.fileClusters.size() > 1 && useDynamicScheduling) {
            checkIfChannelReallocationRequired(estimatedCompletionTimes);
        }
    }

    // This function implements dynamic scheduling. Dynamic scheduling is used to re-assign channels from fast
    // fileClusters to slow fileClusters to make all run as similar pace
    public void checkIfChannelReallocationRequired(double[] estimatedCompletionTimes) {

        // if any channel reallocation is ongoing, then don't go for another!
        for (ChannelModule.ChannelPair cp : ftpClient.channelList) {
            if (cp.isConfigurationChanged) {
                return;
            }
        }
        List<Integer> blacklist = Lists.newArrayListWithCapacity(ftpClient.fileClusters.size());
        int curSlowChunkId, curFastChunkId;
        while (true) {
            double maxDuration = Double.NEGATIVE_INFINITY;
            double minDuration = Double.POSITIVE_INFINITY;
            curSlowChunkId = -1;
            curFastChunkId = -1;
            for (int i = 0; i < estimatedCompletionTimes.length; i++) {
                XferList fileList = ftpClient.fileClusters.get(i).getRecords();
                if (estimatedCompletionTimes[i] == -1 || blacklist.contains(i)) {
                    continue;
                }
                if (estimatedCompletionTimes[i] > maxDuration && fileList.count() > 0) {
                    maxDuration = estimatedCompletionTimes[i];
                    curSlowChunkId = i;
                }
                if (estimatedCompletionTimes[i] < minDuration && fileList.channels.size() > 1) {
                    minDuration = estimatedCompletionTimes[i];
                    curFastChunkId = i;
                }
            }
            System.out.println("CurrentSlow:" + curSlowChunkId + " CurrentFast:" + curFastChunkId +
                    " PrevSlow:" + slowChunkId + " PrevFast:" + fastChunkId + " Period:" + (period + 1));
            if (curSlowChunkId == -1 || curFastChunkId == -1 || curSlowChunkId == curFastChunkId) {
                for (int i = 0; i < estimatedCompletionTimes.length; i++) {
                    System.out.println("Estimated time of :" + i + " " + estimatedCompletionTimes[i]);
                }
                break;
            }
            XferList slowChunk = ftpClient.fileClusters.get(curSlowChunkId).getRecords();
            XferList fastChunk = ftpClient.fileClusters.get(curFastChunkId).getRecords();
            double slowChunkFinTime = Double.MAX_VALUE, fastChunkFinTime;
            period++;
            if (slowChunk.channels.size() > 0) {
                slowChunkFinTime = slowChunk.estimatedFinishTime * slowChunk.channels.size() / (slowChunk.channels.size() + 1);
            }
            fastChunkFinTime = fastChunk.estimatedFinishTime * fastChunk.channels.size() / (fastChunk.channels.size() - 1);
            if (period >= 3 && (curSlowChunkId == slowChunkId || curFastChunkId == fastChunkId)) {
                if (slowChunkFinTime >= fastChunkFinTime * 2) {
                    //System.out.println("total fileClusters  " + ftpClient.ccs.size());
                    synchronized (fastChunk) {
                        ChannelModule.ChannelPair transferringChannel = fastChunk.channels.get(fastChunk.channels.size() - 1);
                        transferringChannel.newChunk = ftpClient.fileClusters.get(curSlowChunkId);
                        transferringChannel.isConfigurationChanged = true;
                        System.out.println("Chunk " + curFastChunkId + "*" + getChannels(fastChunk) +  " is giving channel " +
                                transferringChannel.getId() + " to chunk " + curSlowChunkId + "*" + getChannels(slowChunk));
                    }
                    period = 0;
                    break;
                } else {
                    if (slowChunk.channels.size() > fastChunk.channels.size()) {
                        blacklist.add(curFastChunkId);
                    } else {
                        blacklist.add(curSlowChunkId);
                    }
                    System.out.println("Blacklisted chunk " + blacklist.get(blacklist.size() - 1));
                }
            } else if (curSlowChunkId != slowChunkId && curFastChunkId != fastChunkId) {
                period = 1;
                break;
            } else if (period < 3) {
                break;
            }
        }
        fastChunkId = curFastChunkId;
        slowChunkId = curSlowChunkId;

    }



    public static class ModellingThread implements Runnable {
        public static Queue<ModellingThread.ModellingJob> jobQueue;
        private final int pastLimit = 4;
        public ModellingThread() {
            jobQueue = new ConcurrentLinkedQueue<>();
        }

        @Override
        public void run() {
            while (!AdaptiveGridFTPClient.isTransferCompleted) {
                if (jobQueue.isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    continue;
                }
                ModellingThread.ModellingJob job = jobQueue.peek();
                FileCluster chunk = job.chunk;

                // If chunk is almost finished, don't update parameters as no gain will be achieved
                XferList xl = chunk.getRecords();
                if(xl.totalTransferredSize >= 0.9 * xl.initialSize || xl.count() <= 2) {
                    return;
                }

                TunableParameters tunableParametersUsed = job.tunableParameters;
                double sampleThroughput = job.sampleThroughput;
                double[] params = Hysteresis.runModelling(chunk, tunableParametersUsed, sampleThroughput,
                        new double[]{ConfigurationParams.cc_rate, ConfigurationParams.p_rate, ConfigurationParams.ppq_rate});
                TunableParameters tunableParametersEstimated = new TunableParameters.Builder()
                        .setConcurrency((int) params[0])
                        .setParallelism((int) params[1])
                        .setPipelining((int) params[2])
                        .setBufferSize((int) AdaptiveGridFTPClient.transferTask.getBufferSize())
                        .build();

                chunk.addToTimeSeries(tunableParametersEstimated, params[params.length-1]);
                System.out.println("New round of " + " estimated params: " + tunableParametersEstimated.toString() + " count:" + chunk.getCountOfSeries());
                jobQueue.remove();
                checkForParameterUpdate(chunk, tunableParametersUsed);
            }
            System.out.println("Leaving modelling connectionThread...");
        }

        void checkForParameterUpdate(FileCluster chunk, TunableParameters currentTunableParameters) {
            // See if previous changes has applied yet
            //if (chunk.getRecords().channels.size() != chunk.getTunableParameters().getConcurrency()) {
            //  return;
            //}
            /*
            for (ChannelPair channel : chunk.getRecords().channels) {
              if (channel.parallelism != chunk.getTunableParameters().getParallelism()) {
                chunk.popFromSeries(); // Dont insert latest probing as it was collected during transition phase
                System.out.println("Channel " + channel.getId() + " P:" + channel.parallelism + " chunkP:" + chunk.getTunableParameters().getParallelism());
                return;
              }
            }
            */

            List<TunableParameters> lastNEstimations = chunk.getLastNFromSeries(pastLimit);
            // If this is first estimation, use it only; otherwise make sure to have pastLimit items
            if (lastNEstimations.size() != 3 && lastNEstimations.size() < pastLimit) {
                return;
            }

            int pastLimit = lastNEstimations.size();
            int ccs[] =  new int[pastLimit];
            int ps[] =  new int[pastLimit];
            int ppqs[] =  new int[pastLimit];
            for (int i = 0; i < pastLimit; i++) {
                ccs[i] = lastNEstimations.get(i).getConcurrency();
                ps[i] = lastNEstimations.get(i).getParallelism();
                ppqs[i] = lastNEstimations.get(i).getPipelining();
            }
            int currentConcurrency = currentTunableParameters.getConcurrency();
            int currentParallelism = currentTunableParameters.getParallelism();
            int currentPipelining = currentTunableParameters.getPipelining();
            int newConcurrency = getUpdatedParameterValue(ccs, currentTunableParameters.getConcurrency());
            int newParallelism = getUpdatedParameterValue(ps, currentTunableParameters.getParallelism());
            int newPipelining = getUpdatedParameterValue(ppqs, currentTunableParameters.getPipelining());
            System.out.println("New parameters estimated:\t" + newConcurrency + "-" + newParallelism + "-" + newPipelining );

            if (newPipelining != currentPipelining) {
                System.out.println("New pipelining " + newPipelining );
                chunk.getRecords().channels.forEach(channel -> channel.setPipelining(newPipelining));
                chunk.getTunableParameters().setPipelining(newPipelining);
            }

            if (Math.abs(newParallelism - currentParallelism) >= 2 ||
                    Math.max(newParallelism, currentParallelism) >= 2 * Math.min(newParallelism, currentParallelism))  {
                System.out.println("New parallelism " + newParallelism );
                for (ChannelModule.ChannelPair channel : chunk.getRecords().channels) {
                    channel.isConfigurationChanged = true;
                    channel.newChunk = chunk;
                }
                chunk.getTunableParameters().setParallelism(newParallelism);
                chunk.clearTimeSeries();
            }
            if (Math.abs(newConcurrency - currentConcurrency) >= 2) {
                System.out.println("New concurrency " + newConcurrency);
                if (newConcurrency > currentConcurrency) {
                    int channelCountToAdd = newConcurrency - currentConcurrency;
                    for (int i = 0; i < chunk.getRecords().channels.size(); i++) {
                        if (chunk.getRecords().channels.get(i).isConfigurationChanged &&
                                chunk.getRecords().channels.get(i).newChunk == null) {
                            chunk.getRecords().channels.get(i).isConfigurationChanged = false;
                            System.out.println("Cancelled closing of channel " + i);
                            channelCountToAdd--;
                        }
                    }
                    while (channelCountToAdd > 0) {
                        XferList.MlsxEntry firstFile;
                        synchronized (chunk.getRecords()) {
                            firstFile = chunk.getRecords().pop();
                        }
                        if (firstFile != null) {
                            TransferChannel transferChannel = new TransferChannel(chunk,
                                    chunk.getRecords().channels.size() + channelCountToAdd, firstFile);
                            executor.submit(transferChannel);
                            channelCountToAdd--;
                        }
                    }
                    System.out.println("New concurrency level became " + (newConcurrency - channelCountToAdd));
                    chunk.getTunableParameters().setConcurrency(newConcurrency - channelCountToAdd);
                }
                else {
                    int randMax = chunk.getRecords().channels.size();
                    for (int i = 0; i < currentConcurrency - newConcurrency; i++) {
                        int random = ThreadLocalRandom.current().nextInt(0, randMax--);
                        chunk.getRecords().channels.get(random).isConfigurationChanged = true;
                        chunk.getRecords().channels.get(random).newChunk = null; // New chunk null means closing channel;
                        System.out.println("Will close of channel " + random);
                    }
                    chunk.getTunableParameters().setConcurrency(newConcurrency);
                }
                chunk.clearTimeSeries();
            }
        }

        int getUpdatedParameterValue (int []pastValues, int currentValue) {
            // System.out.println("Past values " + currentValue + ", "+ Arrays.toString(pastValues));

            boolean isLarger = pastValues[0] > currentValue;
            boolean isAllLargeOrSmall = true;
            for (int i = 0; i < pastValues.length; i++) {
                if ((isLarger && pastValues[i] <= currentValue) ||
                        (!isLarger && pastValues[i] >= currentValue)) {
                    isAllLargeOrSmall = false;
                    break;
                }
            }

            if (isAllLargeOrSmall) {
                int sum = 0;
                for (int i = 0; i< pastValues.length; i++) {
                    sum += pastValues[i];
                }
                System.out.println("Sum: " + sum + " length " + pastValues.length);
                return (int)Math.round(sum/(1.0 * pastValues.length));
            }
            return currentValue;
        }

        public static class ModellingJob {
            private final FileCluster chunk;
            private final TunableParameters tunableParameters;
            private final double sampleThroughput;

            public ModellingJob (FileCluster chunk, TunableParameters tunableParameters, double sampleThroughput) {
                this.chunk = chunk;
                this.tunableParameters = tunableParameters;
                this.sampleThroughput = sampleThroughput;
            }
        }
    }

    public class TransferMonitor implements Runnable {
        final int interval = 1000;
        int timer = 0;
        Writer writer;

        @Override
        public void run() {
            try {
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("inst-throughput.txt"), "utf-8"));
                initializeMonitoring();
                Thread.sleep(interval);
                while (!AdaptiveGridFTPClient.isTransferCompleted) {
                    timer += interval / 1000;
                    monitorChannels(interval / 1000, writer, timer);
                    Thread.sleep(interval);
                }
                System.out.println("Leaving monitoring...");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
