package me.shenfeng.dns;

import static java.util.concurrent.Executors.newCachedThreadPool;
import static me.shenfeng.Utils.isIP;
import static me.shenfeng.Utils.toBytes;
import static me.shenfeng.Utils.toInt;
import static me.shenfeng.dns.DnsClientConstant.DNS_UNKOWN_HOST;
import static org.jboss.netty.buffer.ChannelBuffers.dynamicBuffer;
import static org.jboss.netty.util.ThreadNameDeterminer.CURRENT;
import static org.jboss.netty.util.ThreadRenamingRunnable.setThreadNameDeterminer;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import me.shenfeng.PrefixThreadFactory;
import me.shenfeng.Utils;

import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.jboss.netty.channel.socket.nio.NioDatagramChannelFactory;

public class DnsClient implements DnsClientConstant {
    final static Random r = new Random();

    private final ConnectionlessBootstrap mBootstrap;
    private final InetSocketAddress[] mDnsServers;
    private final DatagramChannel c;
    private Thread mTimeoutThread;
    private final DnsClientConfig mConf;
    private final ConcurrentHashMap<Pair, DnsResponseFuture> mContext = new ConcurrentHashMap<Pair, DnsResponseFuture>();

    public DnsClient() {
        this(new DnsClientConfig());
    }

    private void startTimeoutThread() {
        mTimeoutThread = new Thread(new Runnable() {
            public void run() {
                try {
                    while (c.isOpen()) {
                        Thread.sleep(mConf.timerInterval);
                        Iterator<Entry<Pair, DnsResponseFuture>> it = mContext
                                .entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<Pair, DnsResponseFuture> e = it.next();
                            DnsResponseFuture r = e.getValue();
                            if (r.isDone() || r.isTimeout()) {
                                it.remove();
                            }
                        }
                    }
                } catch (InterruptedException ignore) {
                }
            }
        }, TIMER_NAME);
        mTimeoutThread.start();
    }

    public DnsClient(DnsClientConfig conf) {
        List<String> servers = Utils.getNameServer();
        mDnsServers = new InetSocketAddress[servers.size()];

        for (int i = 0; i < servers.size(); ++i) {
            mDnsServers[i] = new InetSocketAddress(servers.get(i), 53);
        }

        mConf = conf;
        setThreadNameDeterminer(CURRENT);
        ExecutorService executor = newCachedThreadPool(new PrefixThreadFactory(
                "DNS"));
        NioDatagramChannelFactory factory = new NioDatagramChannelFactory(
                executor, 1);
        mBootstrap = new ConnectionlessBootstrap(factory);
        mBootstrap.setPipelineFactory(new DnsClientPipelineFactory(mContext));
        c = (DatagramChannel) mBootstrap.bind(new InetSocketAddress(0));
        startTimeoutThread();
    }

    public DnsResponseFuture resolve(final String host) {
        final DnsResponseFuture future = new DnsResponseFuture(
                mConf.dnsTimeout);
        if (isIP(host)) {
            future.done(host);
            return future;
        } else {
            final int id = r.nextInt(65536);
            final ChannelBuffer buffer = encodeDnsRequest(id, host);
            c.write(buffer, mDnsServers[id % mDnsServers.length]);
            mContext.put(new Pair(host, id), future);
        }
        return future;
    }

    public void close() {
        c.close().awaitUninterruptibly();
        mTimeoutThread.interrupt();
        mBootstrap.releaseExternalResources();
    }

    static ChannelBuffer encodeDnsRequest(final int id, final String domain) {
        final ChannelBuffer buffer = dynamicBuffer();
        buffer.writeBytes(toBytes(id));
        buffer.writeBytes(FLAGS_PARAMS);

        int start = 0;
        final int length = domain.length();
        for (int i = 0; i < length; ++i) {
            if (domain.charAt(i) == '.') {
                buffer.writeByte(i - start);
                buffer.writeBytes(domain.substring(start, i).getBytes());
                start = i + 1;
            }
        }
        buffer.writeByte(length - start);
        buffer.writeBytes(domain.substring(start).getBytes());
        buffer.writeByte(0);
        buffer.writeBytes(A_IN);
        return buffer;
    }
}

class DnsClientPipelineFactory implements ChannelPipelineFactory {
    private final DnsResponseHandler handler;

    public DnsClientPipelineFactory(
            ConcurrentMap<Pair, DnsResponseFuture> context) {
        handler = new DnsResponseHandler(context);
    }

    public ChannelPipeline getPipeline() throws Exception {
        return Channels.pipeline(handler);
    }

}

class DnsResponseHandler extends SimpleChannelHandler {

    private final ConcurrentMap<Pair, DnsResponseFuture> mContext;

    public DnsResponseHandler(ConcurrentMap<Pair, DnsResponseFuture> meta) {
        mContext = meta;
    }

    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
            throws Exception {
        ChannelBuffer o = ((ChannelBuffer) e.getMessage()).slice();
        byte[] array = o.array();
        final int id = toInt(array);
        int start = 12;
        int length = array[start];
        int answers = array[7];

        while (length != 0) {
            start = length + start + 1;
            length = array[start];
            array[start] = '.';
        }

        String host = new String(array, 13, start - 13);
        start += 5;// skip query type and class

        final DnsResponseFuture future = mContext.get(new Pair(host, id));
        if (future != null) {
            int type, datalength;
            for (int i = 0; i < answers; ++i) {
                start += 2;// skip name
                type = toInt(array, start);
                start += 8; // skip type, class, ttl
                datalength = toInt(array, start);

                start += 2; // skip data length
                if (type == 1) { // A
                    StringBuilder sb = new StringBuilder(15);
                    for (int j = 0; j < 4; ++j) {
                        int b = toInt(array[start + j]);
                        sb.append(b).append('.');
                    }
                    String ip = sb.subSequence(0, sb.length() - 1).toString();
                    future.done(ip);
                    break;
                }
                start += datalength;
            }

            if (answers == 0) {
                future.done(DNS_UNKOWN_HOST);
            }
        }
    }
}

class Pair {
    final String host;
    final int id;

    public Pair(String host, int id) {
        this.host = host;
        this.id = id;
    }

    public boolean equals(Object obj) {
        if (obj instanceof Pair) {
            Pair rhs = (Pair) obj;
            return rhs.host.equals(host) && id == rhs.id;
        }
        return false;
    }

    public String toString() {
        return host + "@" + id;
    }

    public int hashCode() {
        return id;// random is good enough
    }
}