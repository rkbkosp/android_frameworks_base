/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am.apm;

import android.net.util.SocketUtils;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens on the {@code oplus_hans} generic netlink {@code events} multicast group, which the
 * vendor kernel uses to report that a frozen uid was touched by binder.
 *
 * <p>Neither the command number nor the multicast group id is a documented ABI: the command id is
 * the {@code hans_cmd_event} module parameter, which user space cannot read, and a generic netlink
 * group id is assigned at family registration time. A message is therefore treated as an event
 * when it arrives on the group we subscribed to and comes from the family we resolved, not because
 * it carries a particular command number. Attributes 1..7 are fixed in the driver; 4..7 are only
 * present on the events sent by {@code hans_send_binder_event()}, so they default to 0 for the
 * older {@code hans_send_event()} ones.
 *
 * <p>The listener is optional infrastructure: a kernel without the family, an emulator, or a
 * SELinux denial has to leave the rest of the adaptive process manager untouched. Every failure is
 * swallowed here, reported once through {@link #describe()}, and never propagates to the caller.
 * The socket work happens on {@link #THREAD_NAME}, so {@link #start()} only spawns that thread.
 */
public final class HansEventClient {
    private static final String TAG = "Apm";

    /** Name of the thread that owns the netlink socket. */
    static final String THREAD_NAME = "ApmHansEvents";

    private static final String FAMILY_NAME = "oplus_hans";
    private static final String EVENT_GROUP_NAME = "events";
    // These are the command and attribute ids registered by oplus_sys_hans.c in this tree.
    private static final int CMD_ADD_UID = 1;
    private static final int CMD_DEL_UID = 2;
    private static final int ATTR_UID = 1;

    // Generic netlink itself: android.system.OsConstants has neither NETLINK_GENERIC nor
    // SOL_NETLINK, so both values come from linux/netlink.h. NETLINK_ADD_MEMBERSHIP is also
    // missing there; netlink(7) documents it as option 1 of the SOL_NETLINK level, taking the
    // multicast group id reported by CTRL_ATTR_MCAST_GRP_ID.
    private static final int NETLINK_GENERIC = 16;
    private static final int SOL_NETLINK = 270;
    private static final int NETLINK_ADD_MEMBERSHIP = 1;

    // Generic netlink controller, include/uapi/linux/genetlink.h. CTRL_ATTR_MCAST_GRP_* are
    // nested inside CTRL_ATTR_MCAST_GROUPS, so their 1 and 2 do not collide with the top level
    // CTRL_ATTR_FAMILY_ID and CTRL_ATTR_FAMILY_NAME.
    private static final int GENL_ID_CTRL = 0x10;
    private static final int CTRL_CMD_GETFAMILY = 3;
    private static final int CTRL_ATTR_FAMILY_ID = 1;
    private static final int CTRL_ATTR_FAMILY_NAME = 2;
    private static final int CTRL_ATTR_MCAST_GROUPS = 7;
    private static final int CTRL_ATTR_MCAST_GRP_NAME = 1;
    private static final int CTRL_ATTR_MCAST_GRP_ID = 2;

    // Event payload attributes, drivers/soc/oplus/mm_bg/oplus_sys_hans.c. Attribute 3 (the
    // signal number) is only sent by the old signal-freeze event and has no field in Listener.
    private static final int ATTR_TARGET_UID = 1;
    private static final int ATTR_EVENT = 2;
    private static final int ATTR_CALLER_UID = 4;
    private static final int ATTR_CALLER_PID = 5;
    private static final int ATTR_TARGET_PID = 6;
    private static final int ATTR_CODE = 7;

    // Netlink framing: net/netlink/af_netlink.c and net/netlink/genetlink.c.
    private static final int NLMSG_HDRLEN = 16;
    private static final int NLMSG_ERROR = 2;
    private static final int NLMSG_OFF_TYPE = 4;
    private static final int NLMSG_OFF_ERROR = NLMSG_HDRLEN;
    private static final int GENL_HDRLEN = 4;
    private static final int NLA_HDRLEN = 4;
    private static final int NLA_TYPE_MASK = 0x3fff;
    private static final int ATTRS_OFFSET = NLMSG_HDRLEN + GENL_HDRLEN;
    private static final int NLM_F_REQUEST = 0x01;
    private static final int NLM_F_ACK = 0x04;
    private static final int CTRL_CMD_NEWFAMILY = 1;
    /** The controller does not validate this inbound, but every caller sends its version. */
    private static final int GENL_CTRL_VERSION = 1;
    private static final int SEQ = 1;

    private static final int READ_BUFFER_BYTES = 64 * 1024;
    /** Upper bound on how long {@link #stop()} can take to be noticed by the reader thread. */
    private static final int POLL_TIMEOUT_MS = 100;
    private static final int SETUP_TIMEOUT_MS = 2_000;
    /** Longest name either side sends is "free_buffer_full". */
    private static final int MAX_NLA_STRING_CHARS = 32;
    private static final int MAX_CONSECUTIVE_READ_FAILURES = 8;

    /**
     * nl_pid 0 and no groups. As a destination it means the kernel. As a bind address it asks the
     * kernel to autobind, which matters: kernel originated multicast carries portid 0, and
     * {@code do_one_broadcast()} in af_netlink.c skips every socket whose own portid equals the
     * sender's, so a socket that stayed unbound would never see an event.
     */
    private static final SocketAddress KERNEL_ADDRESS = SocketUtils.makeNetlinkSocketAddress(0, 0);

    /** One event from the {@code events} group. Called on {@link #THREAD_NAME}. */
    public interface Listener {
        /**
         * @param event      one of FROZEN_TRANS, free_buffer_full, packet, signal-freeze, or
         *                   whatever a newer driver adds.
         * @param targetUid  uid of the frozen process the event is about.
         * @param callerUid  uid that touched it, 0 on events that do not carry a caller.
         * @param callerPid  pid of the caller, 0 on events that do not carry one.
         * @param targetPid  pid of the frozen process, 0 on events that do not carry one.
         * @param code       binder transaction code, 0 on events that do not carry one.
         */
        void onEvent(String event, int targetUid, int callerUid, int callerPid, int targetPid,
                int code);
    }

    private final Listener mListener;

    private volatile boolean mStarted;
    private volatile boolean mStopped;
    private volatile boolean mSubscribed;
    private volatile String mFailure;
    private volatile int mFamilyId = -1;
    private volatile int mGroupId = -1;
    private volatile Thread mWorker;
    /** Latest requested kernel state per uid; only the socket thread sends commands. */
    private final ConcurrentHashMap<Integer, Boolean> mPendingFrozen = new ConcurrentHashMap<>();
    /** Reader thread only: a failure talks about itself once, not once per event. */
    private boolean mWarned;

    public HansEventClient(Listener listener) {
        mListener = Objects.requireNonNull(listener);
    }

    /**
     * Spawns the reader thread and returns. It is single shot: a second call, or a call after
     * {@link #stop()}, does nothing.
     */
    public void start() {
        synchronized (this) {
            if (mStarted) {
                return;
            }
            mStarted = true;
        }
        try {
            final Thread worker = new Thread(this::runWorker, THREAD_NAME);
            worker.setDaemon(true);
            mWorker = worker;
            worker.start();
        } catch (Exception e) {
            markUnavailable(describeFailure("worker thread", e));
        }
    }

    /**
     * Asks the reader thread to stop. The thread waits on the descriptor with a bounded poll and
     * never blocks in recvfrom, so it exits shortly after this returns and closes the descriptor
     * itself; closing it here could hand a recycled descriptor number to whoever opens the next
     * one.
     */
    public void stop() {
        synchronized (this) {
            mStopped = true;
            mSubscribed = false;
        }
        final Thread worker = mWorker;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Publish an APM freeze transition without doing socket I/O under the APM lock. */
    public void setFrozen(int uid, boolean frozen) {
        if (uid >= 0 && !mStopped) {
            mPendingFrozen.put(uid, frozen);
        }
    }

    /** True while the multicast subscription is in place, that is, while events can arrive. */
    public boolean isRunning() {
        return mSubscribed;
    }

    /**
     * One line for dumpsys: the resolved family and group, or why this phone mostly will not have
     * them. It is one of
     * {@code hansEvents idle}, {@code hansEvents starting},
     * {@code hansEvents running family=N group=N}, {@code hansEvents stopped}, or
     * {@code hansEvents unavailable (reason)}. A reason once recorded stays, so a client that was
     * torn down still explains itself.
     */
    public String describe() {
        final String failure = mFailure;
        if (failure != null) {
            return "hansEvents unavailable (" + failure + ")";
        }
        if (mSubscribed) {
            return "hansEvents running family=" + mFamilyId + " group=" + mGroupId;
        }
        if (!mStarted) {
            return "hansEvents idle";
        }
        return mStopped ? "hansEvents stopped" : "hansEvents starting";
    }

    /**
     * Records that this phone cannot provide events, logs once, and gives up. Called on the reader
     * thread, and by the unit test to cover the degradation path without a kernel to talk to.
     */
    void markUnavailable(String reason) {
        if (mStopped || mFailure != null) {
            return;
        }
        mFailure = reason;
        mSubscribed = false;
        warnOnce("unavailable: " + reason);
    }

    private void runWorker() {
        FileDescriptor fd = null;
        try {
            fd = openSocket();
            resolve(fd);
            subscribe(fd);
            synchronized (this) {
                if (mStopped) {
                    return; // stop() ran while we were resolving; the finally closes the socket
                }
                mSubscribed = true;
            }
            readLoop(fd, new byte[READ_BUFFER_BYTES], pollFds(fd));
        } catch (Exception e) {
            markUnavailable(reasonOf(e));
        } finally {
            mSubscribed = false;
            closeQuietly(fd);
        }
    }

    private FileDescriptor openSocket() throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, NETLINK_GENERIC);
            Os.bind(fd, KERNEL_ADDRESS);
            Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
            return fd;
        } catch (Exception e) {
            closeQuietly(fd);
            throw failure("netlink socket", e);
        }
    }

    private void subscribe(FileDescriptor fd) throws IOException {
        try {
            Os.setsockoptInt(fd, SOL_NETLINK, NETLINK_ADD_MEMBERSHIP, mGroupId);
        } catch (Exception e) {
            throw failure("NETLINK_ADD_MEMBERSHIP " + mGroupId, e);
        }
    }

    /**
     * Resolves the family and the {@code events} group id. Both come from the single
     * CTRL_CMD_GETFAMILY reply, which always carries the multicast groups; asking for the family
     * id first and doing a second round trip is not needed.
     */
    private void resolve(FileDescriptor fd) throws IOException {
        final byte[] buf = new byte[READ_BUFFER_BYTES];
        final StructPollfd[] fds = pollFds(fd);
        try {
            sendGetFamily(fd);
        } catch (Exception e) {
            throw failure("GETFAMILY", e);
        }
        final long deadline = SystemClock.uptimeMillis() + SETUP_TIMEOUT_MS;
        while (!mStopped) {
            final int remaining = (int) (deadline - SystemClock.uptimeMillis());
            if (remaining <= 0) {
                throw new IOException("no reply to GETFAMILY for " + FAMILY_NAME);
            }
            final int len;
            try {
                len = receive(fd, buf, fds, remaining);
            } catch (ErrnoException e) {
                if (isRetryable(e.errno)) {
                    continue;
                }
                throw failure("GETFAMILY", e);
            } catch (SocketException e) {
                throw failure("GETFAMILY", e);
            }
            if (len <= 0) {
                continue;
            }
            final int error = parseErrorCode(buf, len);
            if (error != 0) {
                throw new IOException(FAMILY_NAME + " lookup failed: " + strerror(error));
            }
            final int familyId = parseFamilyId(buf, len);
            if (familyId <= 0) {
                continue;
            }
            final int groupId = parseMcastGroupId(buf, len, EVENT_GROUP_NAME);
            if (groupId <= 0) {
                throw new IOException(FAMILY_NAME + " has no '" + EVENT_GROUP_NAME
                        + "' multicast group");
            }
            mFamilyId = familyId;
            mGroupId = groupId;
            return;
        }
        throw new IOException("stopped while resolving " + FAMILY_NAME);
    }

    private void readLoop(FileDescriptor fd, byte[] buf, StructPollfd[] fds) {
        int failures = 0;
        while (!mStopped) {
            try {
                flushFrozen(fd);
                final int len = receive(fd, buf, fds, POLL_TIMEOUT_MS);
                failures = 0;
                if (len > 0) {
                    final int error = parseErrorCode(buf, len);
                    if (error != 0 && error != -OsConstants.ENOENT) {
                        markUnavailable("uid update rejected: " + strerror(error));
                        return;
                    }
                    try {
                        parseEvent(buf, len, mFamilyId, mListener);
                    } catch (Exception e) {
                        // parseEvent is pure and bounds checked, so this is the listener's fault;
                        // one warning is enough and the stream keeps running.
                        warnOnce("listener failed: " + e);
                    }
                }
            } catch (ErrnoException e) {
                if (isRetryable(e.errno)) {
                    continue;
                }
                if (++failures >= MAX_CONSECUTIVE_READ_FAILURES) {
                    markUnavailable(describeFailure("poll/recvfrom", e));
                    return;
                }
            } catch (Exception e) {
                if (++failures >= MAX_CONSECUTIVE_READ_FAILURES) {
                    markUnavailable(describeFailure("poll/recvfrom", e));
                    return;
                }
            }
        }
    }

    private void flushFrozen(FileDescriptor fd) throws ErrnoException, SocketException {
        for (java.util.Map.Entry<Integer, Boolean> entry : mPendingFrozen.entrySet()) {
            final int uid = entry.getKey();
            final boolean frozen = entry.getValue();
            final byte[] message = buildUidCommand(mFamilyId, uid, frozen);
            Os.sendto(fd, message, 0, message.length, 0, KERNEL_ADDRESS);
            mPendingFrozen.remove(uid, frozen);
        }
    }

    /** One generic-netlink request matching the driver's add/remove uid operations. */
    static byte[] buildUidCommand(int familyId, int uid, boolean frozen) {
        final ByteBuffer message = ByteBuffer.allocate(ATTRS_OFFSET + 8)
                .order(ByteOrder.nativeOrder());
        message.putInt(message.capacity());
        message.putShort((short) familyId);
        message.putShort((short) NLM_F_REQUEST);
        message.putInt(SEQ);
        message.putInt(0);
        message.put((byte) (frozen ? CMD_ADD_UID : CMD_DEL_UID));
        message.put((byte) 1);
        message.putShort((short) 0);
        message.putShort((short) 8);
        message.putShort((short) ATTR_UID);
        message.putInt(uid);
        return message.array();
    }

    /**
     * Waits up to {@code timeoutMs} for one datagram and copies it into {@code buf}.
     *
     * @return bytes copied, or 0 when the wait expired.
     */
    private static int receive(FileDescriptor fd, byte[] buf, StructPollfd[] fds, int timeoutMs)
            throws ErrnoException, SocketException {
        if (Os.poll(fds, timeoutMs) <= 0) {
            return 0;
        }
        return Os.recvfrom(fd, buf, 0, buf.length, 0, null /* srcAddress: netlink is not inet */);
    }

    private static void sendGetFamily(FileDescriptor fd) throws ErrnoException, SocketException {
        final byte[] name = FAMILY_NAME.getBytes(StandardCharsets.US_ASCII);
        final int attrLen = NLA_HDRLEN + name.length + 1; // NLA_STRING, NUL terminated
        final int messageLen = ATTRS_OFFSET + align4(attrLen);
        final ByteBuffer message = ByteBuffer.allocate(messageLen).order(ByteOrder.nativeOrder());
        message.putInt(messageLen);
        message.putShort((short) GENL_ID_CTRL);
        message.putShort((short) (NLM_F_REQUEST | NLM_F_ACK));
        message.putInt(SEQ);
        message.putInt(0);
        message.put((byte) CTRL_CMD_GETFAMILY);
        message.put((byte) GENL_CTRL_VERSION);
        message.putShort((short) 0);
        message.putShort((short) attrLen);
        message.putShort((short) CTRL_ATTR_FAMILY_NAME);
        message.put(name);
        message.put((byte) 0);
        // The attribute pad and the header are already zero.
        Os.sendto(fd, message.array(), 0, messageLen, 0, KERNEL_ADDRESS);
    }

    /**
     * Decodes one event datagram.
     *
     * <p>Pure, and therefore directly testable: it never touches the socket. {@code familyId} is
     * the only filter, which is what "tolerant of the command number" means here; the attributes
     * present are reported as they are, so an event name this build has never heard of still
     * reaches the listener.
     *
     * @return true when the message was a well formed event and the listener was called.
     */
    static boolean parseEvent(byte[] buf, int len, int familyId, Listener out) {
        if (buf == null || out == null || len > buf.length || len < ATTRS_OFFSET) {
            return false;
        }
        final ByteBuffer message = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.nativeOrder());
        final int messageLen = message.getInt(0);
        // A truncated nlmsghdr is what a datagram larger than our buffer looks like.
        if (messageLen < ATTRS_OFFSET || messageLen > len) {
            return false;
        }
        if ((message.getShort(NLMSG_OFF_TYPE) & 0xffff) != familyId) {
            return false;
        }
        boolean hasTargetUid = false;
        int targetUid = 0;
        int callerUid = 0;
        int callerPid = 0;
        int targetPid = 0;
        int code = 0;
        String event = null;
        for (int at = ATTRS_OFFSET; at + NLA_HDRLEN <= messageLen; ) {
            final int attrLen = message.getShort(at) & 0xffff;
            if (attrLen < NLA_HDRLEN || at + attrLen > messageLen) {
                return false;
            }
            final int payload = at + NLA_HDRLEN;
            final int payloadLen = attrLen - NLA_HDRLEN;
            switch ((message.getShort(at + 2) & 0xffff) & NLA_TYPE_MASK) {
                case ATTR_TARGET_UID:
                    if (payloadLen < 4) {
                        return false;
                    }
                    targetUid = message.getInt(payload);
                    hasTargetUid = true;
                    break;
                case ATTR_EVENT:
                    event = parseNlaString(message, payload, payloadLen);
                    if (event == null) {
                        return false;
                    }
                    break;
                case ATTR_CALLER_UID:
                    if (payloadLen < 4) {
                        return false;
                    }
                    callerUid = message.getInt(payload);
                    break;
                case ATTR_CALLER_PID:
                    if (payloadLen < 4) {
                        return false;
                    }
                    callerPid = message.getInt(payload);
                    break;
                case ATTR_TARGET_PID:
                    if (payloadLen < 4) {
                        return false;
                    }
                    targetPid = message.getInt(payload);
                    break;
                case ATTR_CODE:
                    if (payloadLen < 4) {
                        return false;
                    }
                    code = message.getInt(payload);
                    break;
                default:
                    // Attribute 3, the signal number, and anything a newer driver adds.
                    break;
            }
            at += align4(attrLen);
        }
        if (!hasTargetUid || event == null) {
            return false;
        }
        out.onEvent(event, targetUid, callerUid, callerPid, targetPid, code);
        return true;
    }

    /**
     * The family id from a CTRL_CMD_GETFAMILY reply, or -1 when this is some other message. The
     * kernel encodes it as a u16.
     */
    static int parseFamilyId(byte[] buf, int len) {
        final ByteBuffer message = controllerReply(buf, len);
        if (message == null) {
            return -1;
        }
        final int attr = findAttribute(message, ATTRS_OFFSET, message.getInt(0),
                CTRL_ATTR_FAMILY_ID);
        if (attr < 0 || (message.getShort(attr) & 0xffff) - NLA_HDRLEN < 2) {
            return -1;
        }
        return message.getShort(attr + NLA_HDRLEN) & 0xffff;
    }

    /**
     * The id of the multicast group called {@code name} in a CTRL_CMD_GETFAMILY reply, or -1. The
     * value is the one NETLINK_ADD_MEMBERSHIP wants; the driver's group is index 0 in the family,
     * but the kernel hands out ids from a global bitmap that reserves 0.
     */
    static int parseMcastGroupId(byte[] buf, int len, String name) {
        final ByteBuffer message = controllerReply(buf, len);
        if (message == null) {
            return -1;
        }
        final int messageLen = message.getInt(0);
        final int groups = findAttribute(message, ATTRS_OFFSET, messageLen, CTRL_ATTR_MCAST_GROUPS);
        if (groups < 0) {
            return -1;
        }
        final int groupsEnd = groups + (message.getShort(groups) & 0xffff);
        for (int at = groups + NLA_HDRLEN; at + NLA_HDRLEN <= groupsEnd; ) {
            final int attrLen = message.getShort(at) & 0xffff;
            if (attrLen < NLA_HDRLEN || at + attrLen > groupsEnd) {
                return -1;
            }
            final int end = at + attrLen;
            final int groupName = findAttribute(message, at + NLA_HDRLEN, end,
                    CTRL_ATTR_MCAST_GRP_NAME);
            if (groupName >= 0 && name.equals(parseNlaString(message, groupName + NLA_HDRLEN,
                    (message.getShort(groupName) & 0xffff) - NLA_HDRLEN))) {
                final int id = findAttribute(message, at + NLA_HDRLEN, end, CTRL_ATTR_MCAST_GRP_ID);
                return id >= 0 && (message.getShort(id) & 0xffff) - NLA_HDRLEN >= 4
                        ? message.getInt(id + NLA_HDRLEN) : -1;
            }
            at += align4(attrLen);
        }
        return -1;
    }

    /**
     * The errno an NLMSG_ERROR message carries, or 0 when this is not an error reply. The
     * NETLINK_ADD_MEMBERSHIP grant that NLM_F_ACK asks for is such an error message with errno 0.
     */
    static int parseErrorCode(byte[] buf, int len) {
        if (buf == null || len > buf.length || len < NLMSG_HDRLEN + 4) {
            return 0;
        }
        final ByteBuffer message = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.nativeOrder());
        if ((message.getShort(NLMSG_OFF_TYPE) & 0xffff) != NLMSG_ERROR) {
            return 0;
        }
        return message.getInt(NLMSG_OFF_ERROR);
    }

    /** The buffer as a little endian header, or null when it is not a controller reply. */
    private static ByteBuffer controllerReply(byte[] buf, int len) {
        if (buf == null || len > buf.length || len < ATTRS_OFFSET) {
            return null;
        }
        final ByteBuffer message = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.nativeOrder());
        final int messageLen = message.getInt(0);
        if (messageLen < ATTRS_OFFSET || messageLen > len) {
            return null;
        }
        if ((message.getShort(NLMSG_OFF_TYPE) & 0xffff) != GENL_ID_CTRL
                || (message.get(NLMSG_HDRLEN) & 0xff) != CTRL_CMD_NEWFAMILY) {
            return null;
        }
        return message;
    }

    /** Offset of the header of the first attribute of {@code type} in [off, end), or -1. */
    private static int findAttribute(ByteBuffer message, int off, int end, int type) {
        for (int at = off; at + NLA_HDRLEN <= end; ) {
            final int attrLen = message.getShort(at) & 0xffff;
            if (attrLen < NLA_HDRLEN || at + attrLen > end) {
                return -1;
            }
            if (((message.getShort(at + 2) & 0xffff) & NLA_TYPE_MASK) == type) {
                return at;
            }
            at += align4(attrLen);
        }
        return -1;
    }

    /**
     * An NLA_STRING payload. The kernel only ever sends short literal names, so anything with an
     * embedded non printable byte or without its NUL is a message we are reading wrong.
     */
    private static String parseNlaString(ByteBuffer message, int offset, int length) {
        int end = -1;
        for (int i = 0; i < length; i++) {
            final byte b = message.get(offset + i);
            if (b == 0) {
                end = i;
                break;
            }
            if (b < 0x20 || b > 0x7e) {
                return null;
            }
        }
        if (end <= 0 || end > MAX_NLA_STRING_CHARS) {
            return null;
        }
        final char[] chars = new char[end];
        for (int i = 0; i < end; i++) {
            chars[i] = (char) message.get(offset + i);
        }
        return new String(chars);
    }

    private static StructPollfd[] pollFds(FileDescriptor fd) {
        final StructPollfd[] fds = new StructPollfd[] { new StructPollfd() };
        fds[0].fd = fd;
        fds[0].events = (short) OsConstants.POLLIN;
        return fds;
    }

    /** EINTR and EAGAIN are a retry; ENOBUFS is one dropped event, not a broken socket. */
    private static boolean isRetryable(int errno) {
        return errno == OsConstants.EINTR || errno == OsConstants.EAGAIN
                || errno == OsConstants.ENOBUFS;
    }

    private static int align4(int length) {
        return (length + 3) & ~3;
    }

    /**
     * A readable errno. A netlink error reply carries it negated (struct nlmsgerr), while
     * libcore's ErrnoException does not, so both spellings end up here.
     */
    private static String strerror(int errno) {
        final int positive = errno < 0 ? -errno : errno;
        return Os.strerror(positive) + " (" + positive + ")";
    }

    private static IOException failure(String step, Exception cause) {
        return new IOException(describeFailure(step, cause), cause);
    }

    private static String describeFailure(String step, Exception e) {
        if (e instanceof ErrnoException) {
            return step + ": " + strerror(((ErrnoException) e).errno);
        }
        return step + ": " + reasonOf(e);
    }

    private static String reasonOf(Exception e) {
        final String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    private void warnOnce(String message) {
        if (mWarned) {
            return;
        }
        mWarned = true;
        Slog.w(TAG, "hans events: " + message);
    }

    private static void closeQuietly(FileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            Os.close(fd);
        } catch (Exception ignored) {
            // Already closed, or closed by the runtime: nothing left to do.
        }
    }
}
