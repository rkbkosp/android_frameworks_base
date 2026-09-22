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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decoder checks for {@link HansEventClient}. Everything here is bytes the kernel would send, so
 * no socket, no netlink, and no oplus kernel is involved; the framing numbers are restated from
 * the kernel ABI rather than taken from the class under test, so a wrong one is caught here.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:HansEventClientTest
 */
public class HansEventClientTest {
    /** A resolved family id. Generic netlink families start above GENL_ID_CTRL (0x10). */
    private static final int FAMILY_ID = 42;
    /** nlctrl, the family the events never come from. */
    private static final int GENL_ID_CTRL = 0x10;
    private static final int NLMSG_ERROR = 2;
    private static final int NLMSG_HDRLEN = 16;
    private static final int GENL_HDRLEN = 4;
    private static final int NLA_HDRLEN = 4;

    private static final int CTRL_CMD_GETFAMILY = 3;
    private static final int CTRL_CMD_NEWFAMILY = 1;
    private static final int CTRL_ATTR_FAMILY_ID = 1;
    private static final int CTRL_ATTR_FAMILY_NAME = 2;
    private static final int CTRL_ATTR_MCAST_GROUPS = 7;
    private static final int CTRL_ATTR_MCAST_GRP_NAME = 1;
    private static final int CTRL_ATTR_MCAST_GRP_ID = 2;

    private static final int ATTR_UID = 1;
    private static final int ATTR_EVENT = 2;
    private static final int ATTR_SIG = 3;
    private static final int ATTR_CALLER_UID = 4;
    private static final int ATTR_CALLER_PID = 5;
    private static final int ATTR_TARGET_PID = 6;
    private static final int ATTR_CODE = 7;

    private static final int TARGET_UID = 10123;
    private static final int CALLER_UID = 1000;
    private static final int CALLER_PID = 4321;
    private static final int TARGET_PID = 5678;
    private static final int CODE = 0x0000002a;

    /**
     * An event command number. Any value has to work: the real one is the {@code hans_cmd_event}
     * module parameter, which user space cannot read, so nothing may filter on it.
     */
    private static final int ANY_EVENT_COMMAND = 9;

    private static final class Recorder implements HansEventClient.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onEvent(String event, int targetUid, int callerUid, int callerPid,
                int targetPid, int code) {
            events.add(event + " " + targetUid + " " + callerUid + " " + callerPid + " "
                    + targetPid + " " + code);
        }
    }

    @Test
    public void binderEventCarriesCallerIdentityWhateverTheCommandNumberIs() {
        final Recorder recorder = new Recorder();
        final byte[] message = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND,
                intAttr(ATTR_UID, TARGET_UID),
                stringAttr(ATTR_EVENT, "FROZEN_TRANS"),
                intAttr(ATTR_CALLER_UID, CALLER_UID),
                intAttr(ATTR_CALLER_PID, CALLER_PID),
                intAttr(ATTR_TARGET_PID, TARGET_PID),
                intAttr(ATTR_CODE, CODE));

        assertTrue(HansEventClient.parseEvent(message, message.length, FAMILY_ID, recorder));
        assertEquals(
                Collections.singletonList("FROZEN_TRANS " + TARGET_UID + " " + CALLER_UID + " "
                        + CALLER_PID + " " + TARGET_PID + " " + CODE),
                recorder.events);
    }

    @Test
    public void oldStyleEventLeavesTheCallerFieldsAtZero() {
        final Recorder recorder = new Recorder();
        // hans_send_event(): uid, name, signal; 4..7 do not exist yet. The signal attribute is
        // not part of the listener contract and has to be skipped rather than rejected.
        final byte[] message = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND,
                intAttr(ATTR_UID, TARGET_UID),
                stringAttr(ATTR_EVENT, "signal-freeze"),
                intAttr(ATTR_SIG, 15));

        assertTrue(HansEventClient.parseEvent(message, message.length, FAMILY_ID, recorder));
        assertEquals(Collections.singletonList("signal-freeze " + TARGET_UID + " 0 0 0 0"),
                recorder.events);
    }

    @Test
    public void eventNameHasToBeAFinishedString() {
        final Recorder recorder = new Recorder();
        final byte[] notAString = { 'F', 'R', 'O', 'Z', 'E', 'N' };
        final byte[] message = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND,
                intAttr(ATTR_UID, TARGET_UID),
                attr(ATTR_EVENT, notAString));

        assertFalse(HansEventClient.parseEvent(message, message.length, FAMILY_ID, recorder));
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void attributeLongerThanTheMessageIsRejected() {
        final Recorder recorder = new Recorder();
        final byte[] message = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND,
                intAttr(ATTR_UID, TARGET_UID),
                stringAttr(ATTR_EVENT, "FROZEN_TRANS"),
                oversizeAttr(ATTR_CODE));

        assertFalse(HansEventClient.parseEvent(message, message.length, FAMILY_ID, recorder));
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void truncatedMessagesAreRejected() {
        final Recorder recorder = new Recorder();
        final byte[] whole = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND,
                intAttr(ATTR_UID, TARGET_UID),
                stringAttr(ATTR_EVENT, "FROZEN_TRANS"));

        assertFalse(HansEventClient.parseEvent(Arrays.copyOf(whole, whole.length - 8),
                whole.length - 8, FAMILY_ID, recorder));
        // Shorter than an nlmsghdr and a genlmsghdr: not worth looking at.
        assertFalse(HansEventClient.parseEvent(whole, NLMSG_HDRLEN + GENL_HDRLEN - 1, FAMILY_ID,
                recorder));
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void messagesFromOtherFamiliesAreIgnored() {
        final Recorder recorder = new Recorder();
        final byte[] attributes = intAttr(ATTR_UID, TARGET_UID);
        final byte[] event = stringAttr(ATTR_EVENT, "FROZEN_TRANS");
        final byte[] fromController =
                genlMessage(GENL_ID_CTRL, ANY_EVENT_COMMAND, attributes, event);
        final byte[] anError = genlMessage(NLMSG_ERROR, 0, attributes, event);
        final byte[] neighbouringFamily =
                genlMessage(FAMILY_ID + 1, ANY_EVENT_COMMAND, attributes, event);

        // The payload is a perfectly good event in all three cases, which is the point.
        assertFalse(HansEventClient.parseEvent(fromController, fromController.length, FAMILY_ID,
                recorder));
        assertFalse(HansEventClient.parseEvent(anError, anError.length, FAMILY_ID, recorder));
        assertFalse(HansEventClient.parseEvent(neighbouringFamily, neighbouringFamily.length,
                FAMILY_ID, recorder));
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    public void familyReplyYieldsTheFamilyAndTheEventsGroup() {
        final byte[] reply = genlMessage(GENL_ID_CTRL, CTRL_CMD_NEWFAMILY,
                stringAttr(CTRL_ATTR_FAMILY_NAME, "oplus_hans"),
                u16Attr(CTRL_ATTR_FAMILY_ID, FAMILY_ID),
                mcastGroups(group(1, "notify", 1), group(2, "events", 5)));

        assertEquals(FAMILY_ID, HansEventClient.parseFamilyId(reply, reply.length));
        assertEquals(5, HansEventClient.parseMcastGroupId(reply, reply.length, "events"));
        assertEquals(1, HansEventClient.parseMcastGroupId(reply, reply.length, "notify"));
        assertEquals(-1, HansEventClient.parseMcastGroupId(reply, reply.length, "missing"));
        // An event message is not a reply.
        final byte[] event = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND, intAttr(ATTR_UID, 1));
        assertEquals(-1, HansEventClient.parseFamilyId(event, event.length));
        assertEquals(-1, HansEventClient.parseMcastGroupId(event, event.length, "events"));
    }

    @Test
    public void errorRepliesAreDistinguishableFromEvents() {
        final byte[] error = errorMessage(-2 /* ENOENT */);
        final byte[] ack = errorMessage(0);
        final byte[] event = genlMessage(FAMILY_ID, ANY_EVENT_COMMAND, intAttr(ATTR_UID, 1));

        assertEquals(-2, HansEventClient.parseErrorCode(error, error.length));
        // NLM_F_ACK answers a successful request with errno 0, which is not a failure.
        assertEquals(0, HansEventClient.parseErrorCode(ack, ack.length));
        assertEquals(0, HansEventClient.parseErrorCode(event, event.length));
        assertEquals(0, HansEventClient.parseErrorCode(event, 8));
    }

    @Test
    public void describeReportsTheReasonAndSurvivesStop() {
        final HansEventClient client = new HansEventClient(new Recorder());
        assertEquals("hansEvents idle", client.describe());
        assertFalse(client.isRunning());

        client.markUnavailable("GETFAMILY: No such file or directory (2)");

        assertFalse(client.isRunning());
        final String unavailable = client.describe();
        assertTrue(unavailable, unavailable.startsWith("hansEvents unavailable"));
        assertTrue(unavailable, unavailable.contains("No such file or directory (2)"));

        // dumpsys has to keep explaining a client that was already torn down.
        client.stop();
        assertFalse(client.isRunning());
        assertTrue(client.describe(), client.describe().contains("No such file or directory (2)"));
    }

    /** One nlmsghdr, one genlmsghdr, then the attributes. */
    private static byte[] genlMessage(int type, int command, byte[]... attributes) {
        int payload = 0;
        for (byte[] attribute : attributes) {
            payload += attribute.length;
        }
        final ByteBuffer message = ByteBuffer.allocate(NLMSG_HDRLEN + GENL_HDRLEN + payload)
                .order(ByteOrder.nativeOrder());
        message.putInt(message.capacity());
        message.putShort((short) type);
        message.putShort((short) 0);
        message.putInt(1);
        message.putInt(0);
        message.put((byte) command);
        message.put((byte) 1);
        message.putShort((short) 0);
        for (byte[] attribute : attributes) {
            message.put(attribute);
        }
        return message.array();
    }

    /** One nlattr. The payload is padded the way nla_put() pads it. */
    private static byte[] attr(int type, byte[] payload) {
        final ByteBuffer attribute = ByteBuffer
                .allocate(NLA_HDRLEN + ((payload.length + 3) & ~3))
                .order(ByteOrder.nativeOrder());
        attribute.putShort((short) (NLA_HDRLEN + payload.length));
        attribute.putShort((short) type);
        attribute.put(payload);
        return attribute.array();
    }

    /** An attribute claiming 64 bytes without carrying them. */
    private static byte[] oversizeAttr(int type) {
        return ByteBuffer.allocate(NLA_HDRLEN).order(ByteOrder.nativeOrder())
                .putShort((short) 64)
                .putShort((short) type)
                .array();
    }

    private static byte[] stringAttr(int type, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        return attr(type, Arrays.copyOf(bytes, bytes.length + 1));
    }

    /**
     * A four byte attribute: the driver's uids and code, and its pids, which are the same bytes
     * read as a signed value.
     */
    private static byte[] intAttr(int type, int value) {
        return attr(type, ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                .putInt(value).array());
    }

    private static byte[] u16Attr(int type, int value) {
        return attr(type, ByteBuffer.allocate(2).order(ByteOrder.nativeOrder())
                .putShort((short) value).array());
    }

    /** NLMSG_ERROR: the errno of the offending request, then its header. */
    private static byte[] errorMessage(int errno) {
        final ByteBuffer message = ByteBuffer.allocate(NLMSG_HDRLEN + 4)
                .order(ByteOrder.nativeOrder());
        message.putInt(message.capacity());
        message.putShort((short) NLMSG_ERROR);
        message.putShort((short) 0);
        message.putInt(1);
        message.putInt(0);
        message.putInt(errno);
        return message.array();
    }

    /** CTRL_ATTR_MCAST_GROUPS: nested attributes holding a name and an id each. */
    private static byte[] mcastGroups(byte[]... groups) {
        int payload = 0;
        for (byte[] group : groups) {
            payload += group.length;
        }
        final ByteBuffer nested = ByteBuffer.allocate(NLA_HDRLEN + payload)
                .order(ByteOrder.nativeOrder());
        nested.putShort((short) (NLA_HDRLEN + payload));
        nested.putShort((short) CTRL_ATTR_MCAST_GROUPS);
        for (byte[] group : groups) {
            nested.put(group);
        }
        return nested.array();
    }

    /**
     * One layer of {@link #mcastGroups}: the kernel numbers multicast groups from 1 within the
     * family, regardless of the id it later reports.
     */
    private static byte[] group(int index, String name, int id) {
        final byte[] nameAttr = stringAttr(CTRL_ATTR_MCAST_GRP_NAME, name);
        final byte[] idAttr = intAttr(CTRL_ATTR_MCAST_GRP_ID, id);
        return nested(index, nameAttr, idAttr);
    }

    private static byte[] nested(int type, byte[]... attributes) {
        int payload = 0;
        for (byte[] attribute : attributes) {
            payload += attribute.length;
        }
        final ByteBuffer nested = ByteBuffer.allocate(NLA_HDRLEN + payload)
                .order(ByteOrder.nativeOrder());
        nested.putShort((short) (NLA_HDRLEN + payload));
        nested.putShort((short) type);
        for (byte[] attribute : attributes) {
            nested.put(attribute);
        }
        return nested.array();
    }
}
