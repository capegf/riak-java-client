package com.basho.riak.client.core.netty;

import com.basho.riak.client.core.RiakMessage;
import com.basho.riak.protobuf.RiakMessageCodes;
import com.basho.riak.protobuf.RiakPB;
import com.basho.riak.protobuf.RiakTsPB;
import com.ericsson.otp.erlang.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;

/**
 * @author Sergey Galkin <srggal at gmail dot com>
 */
public class RiakTTBCodec extends ByteToMessageCodec<RiakMessage> {
    private final static Logger logger = LoggerFactory.getLogger(RiakTTBCodec.class);
    private RiakMessageCodec pbCodec = new RiakMessageCodec();

    private static final OtpErlangAtom undefined = new OtpErlangAtom("undefined");
    private static final OtpErlangAtom tscell = new OtpErlangAtom("tscell");
    private static final OtpErlangAtom tsrow = new OtpErlangAtom("tsrow");
    private static final OtpErlangAtom tsputreq = new OtpErlangAtom("tsputreq");
    private static final OtpErlangAtom tsqueryreq = new OtpErlangAtom("tsqueryreq");
    private static final OtpErlangAtom tsinterpolation = new OtpErlangAtom("tsinterpolation");
    private static final OtpErlangAtom tsgetreq = new OtpErlangAtom("tsgetreq");

    private static final OtpErlangAtom _false = new OtpErlangAtom("false");
    private static final OtpErlangList EMPTY_ERLANG_LIST = new OtpErlangList();
    private static final OtpErlangTuple EMPTY_ERLANG_TSCELL = new OtpErlangTuple(new OtpErlangObject[]{
            tscell, undefined, undefined, undefined, undefined, undefined});

    private boolean isTTBMessage(byte code) {
        switch (code) {
            case RiakMessageCodes.MSG_TsPutReq:
            case RiakMessageCodes.MSG_TsGetReq:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RiakMessage msg, ByteBuf out) throws Exception {
        final OtpErlangTuple t;
        final boolean isTtbOn = isTTBOnForChanel(ctx.channel());

        if (isTtbOn && isTTBMessage(msg.getCode())) {
            logger.trace("Encoding message {}, encoding '{}', is deferred: {}, channel '{}' {}",
                    msg.getCode(), "TTB", !msg.isEncoded(), ctx.channel().hashCode(), ctx.channel());
            switch (msg.getCode()) {
                case RiakMessageCodes.MSG_TsPutReq:
                    t = encodeTsPut(msg);
                    break;
                case RiakMessageCodes.MSG_TsGetReq:
                    t = encodeTsGet(msg);
                    break;
//            case RiakMessageCodes.MSG_TsQueryReq:
//                t = encodeTsQuery(msg);
//                break;
                default:
                    throw new IllegalStateException("Can't encode TTB request, unsupported message with code " + msg.getCode());
            }
        } else {
            logger.trace("Encoding message {}, encoding '{}', is deferred: {}, channel '{}' {}",
                    msg.getCode(), "PB", !msg.isEncoded(), ctx.channel().hashCode(), ctx.channel());

            if (!msg.isEncoded()) {
                final GeneratedMessage.Builder<?> reqBuilder = msg.getDataObject();
                msg.setData(reqBuilder.build().toByteArray());
            }
            pbCodec.encode(ctx, msg, out);
            return;
        }

        final OtpOutputStream os = new OtpOutputStream(t);
        byte data[] = os.toByteArray();

        int length = data.length+2;
        out.writeInt(length);


        out.writeByte(msg.getCode());

        /**
         * DO A TOP SECRET HACK
         *
         * It seems that the message is missing the 131 tag required by term_to_binary.
         * As is evident from the Java output, this tag is not being added by jinterface encode.
         * If I simply add 131 to the beginning of the binary it decodes correctly.
         *
         * http://stackoverflow.com/questions/15189447/jinterface-to-create-external-erlang-term
         */
        out.writeByte(131);
        out.writeBytes(data);
    }

    private static OtpErlangTuple pbCellToTtb(RiakTsPB.TsCell c) {
        final OtpErlangObject o[];

        if (c.hasVarcharValue()) {
            o = new OtpErlangObject[]
                    {tscell,
                            pbStrToTtb(c.getVarcharValue()),
                            undefined, undefined, undefined, undefined};
        } else if (c.hasSint64Value()) {
            o = new OtpErlangObject[]
                    {tscell,
                            undefined,
                            new OtpErlangLong(c.getSint64Value()), undefined, undefined, undefined};
        } else if (c.hasTimestampValue()) {
            o = new OtpErlangObject[]
                    {tscell,
                            undefined, undefined,
                            new OtpErlangLong(c.getTimestampValue()),
                            undefined, undefined};
        } else if (c.hasBooleanValue()) {
            o = new OtpErlangObject[]
                    {tscell,
                            undefined, undefined, undefined,
                            new OtpErlangBoolean(c.getBooleanValue()),
                            undefined};
        } else if (c.hasDoubleValue()) {
            o = new OtpErlangObject[]
                    {tscell,
                            undefined, undefined, undefined, undefined,
                            new OtpErlangDouble(c.getDoubleValue())};
        } else {
            return EMPTY_ERLANG_TSCELL;
        }

        return new OtpErlangTuple(o);
    }

    private static OtpErlangList pbCellsToTtb(List<RiakTsPB.TsCell> pbCells) {
        final OtpErlangObject cells[] = new OtpErlangObject[pbCells.size()];
        for (int i = 0; i < cells.length; ++i) {
            final RiakTsPB.TsCell c = pbCells.get(i);
            cells[i] = pbCellToTtb(c);
        }

        return new OtpErlangList(cells);
    }

    private static OtpErlangTuple pbRowToTtb(RiakTsPB.TsRow r) {
        return new OtpErlangTuple(new OtpErlangObject[] {tsrow, pbCellsToTtb(r.getCellsList())});
    }

    private static OtpErlangBinary pbStrToTtb(ByteString bs) {
        return new OtpErlangBinary(bs.toByteArray());
    }

    private static OtpErlangTuple pbInterpolationToTtb(RiakTsPB.TsInterpolation interpolation) {
        return new OtpErlangTuple(new OtpErlangObject[] {tsinterpolation, pbStrToTtb(interpolation.getBase()), EMPTY_ERLANG_LIST});
    }

    private static OtpErlangTuple encodeTsPut(RiakMessage msg) throws InvalidProtocolBufferException, UnsupportedEncodingException {
        final RiakTsPB.TsPutReqOrBuilder req;
        if (msg.isEncoded()) {
            req = RiakTsPB.TsPutReq.parseFrom(msg.getData());
        }else {
            req = msg.getDataObject();
        }

        final OtpErlangObject rows[] = new OtpErlangObject[req.getRowsCount()];
        for (int i = 0; i < rows.length; ++i) {
            final RiakTsPB.TsRow r = req.getRows(i);
            rows[i] = pbRowToTtb(r);
        }

        final OtpErlangObject[] elems = new OtpErlangObject[]
                {tsputreq, pbStrToTtb(req.getTable()),
                        new OtpErlangList(),
                        new OtpErlangList(rows)};

        return new OtpErlangTuple(elems);
    }

    private OtpErlangTuple encodeTsQuery(RiakMessage msg) throws InvalidProtocolBufferException {
        final RiakTsPB.TsQueryReq req = RiakTsPB.TsQueryReq.parseFrom(msg.getData());

        final OtpErlangObject[] elems = new OtpErlangObject[]
                {tsqueryreq, pbInterpolationToTtb(req.getQuery()), _false};

        return new OtpErlangTuple(elems);
    }

    private static OtpErlangTuple encodeTsGet(RiakMessage msg) throws InvalidProtocolBufferException {
        final RiakTsPB.TsGetReqOrBuilder req;
        if (msg.isEncoded()) {
            req = RiakTsPB.TsGetReq.parseFrom(msg.getData());

        } else {
            req = msg.getDataObject();
        }

        final OtpErlangObject[] elems = new OtpErlangObject[]
                {tsgetreq, pbStrToTtb(req.getTable()),
                        pbCellsToTtb(req.getKeyList()),
                        undefined};

        return new OtpErlangTuple(elems);
    }

    private static ByteString ttbBinaryToPb(OtpErlangBinary b) {
        return ByteString.copyFromUtf8(new String(b.binaryValue()));
    }

    private static List<RiakTsPB.TsRow> ttbRowsToPb(OtpErlangList ttbRows) {
        final RiakTsPB.TsRow rows[] = new RiakTsPB.TsRow[ttbRows.arity()];

        for (int i=0; i<rows.length; ++i){
            final OtpErlangTuple r = (OtpErlangTuple) ttbRows.elementAt(i);
            final OtpErlangList ttbCells = (OtpErlangList) r.elementAt(1);

            final RiakTsPB.TsCell cells[] = new RiakTsPB.TsCell[ttbCells.arity()];
            for (int j=0; j<cells.length; ++j){
                final OtpErlangTuple c = (OtpErlangTuple) ttbCells.elementAt(j);
                cells[j] = ttbCellToPb(c);
            }

            rows[i] = RiakTsPB.TsRow.newBuilder().addAllCells(Arrays.asList(cells)).build();
        }
        return Arrays.asList(rows);
    }

    private static List<RiakTsPB.TsColumnDescription> ttbColumnDescriptionToPb(OtpErlangList ttbDescrs) {
        final RiakTsPB.TsColumnDescription descrs[] = new RiakTsPB.TsColumnDescription[ttbDescrs.arity()];
        for (int i=0; i<descrs.length; ++i){
            final OtpErlangTuple d = (OtpErlangTuple) ttbDescrs.elementAt(i);
            final OtpErlangAtom a = (OtpErlangAtom) d.elementAt(2);
            final RiakTsPB.TsColumnType ct = RiakTsPB.TsColumnType.valueOf(a.atomValue());

            descrs[i] = RiakTsPB.TsColumnDescription.newBuilder()
                    .setName( ttbBinaryToPb((OtpErlangBinary)d.elementAt(1)))
                    .setType(ct)
                    .build();
        }

        return Arrays.asList(descrs);
    }

    private static RiakTsPB.TsCell ttbCellToPb(OtpErlangTuple c) {
        final RiakTsPB.TsCell.Builder builder = RiakTsPB.TsCell.newBuilder();

        for (int i=1; i< c.arity(); ++i) {
            final OtpErlangObject o = c.elementAt(i);
            if (!(o instanceof OtpErlangAtom) || !((OtpErlangAtom)o).atomValue().equals(undefined.atomValue()) ){

                switch (i) {
                    case 1:
                        builder.setVarcharValue( ttbBinaryToPb((OtpErlangBinary)o));
                        break;
                    case 2:
                        builder.setSint64Value( ((OtpErlangLong)o).longValue());
                        break;
                    case 3:
                        builder.setTimestampValue(((OtpErlangLong)o).longValue());
                        break;
                    case 4:
                        builder.setBooleanValue(((OtpErlangAtom)o).booleanValue());
                        break;
                    case 5:
                        builder.setDoubleValue(((OtpErlangDouble)o).doubleValue());
                        break;
                    default:
                        throw new IllegalStateException("Unsupported cell value type");
                }
            }
        }
        return builder.build();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {

        final boolean isTTBOn = isTTBOnForChanel(ctx.channel());

        // Make sure we have 4 bytes
        if (in.readableBytes() >= 4) {
            in.markReaderIndex();

            int length = in.readInt();
            // See if we have the full frame.
            if (in.readableBytes() < length) {
                in.capacity(length);
                in.resetReaderIndex();
                return;
            }

            if (!isTTBOn) {
                // Decode as PB

                in.resetReaderIndex();
                pbCodec.decode(null, in, out);

                if (out.size() == 1) {
                    RiakMessage m = (RiakMessage) out.get(0);
                    switch ( m.getCode()) {
                        case RiakMessageCodes.MSG_ToggleEncodingResp:
                            final RiakPB.RpbToggleEncodingResp r = RiakPB.RpbToggleEncodingResp.PARSER.parseFrom(m.getData());
                            updateTTBUsageForChannel(ctx.channel(), r.getUseNative());
                            logger.debug("Native/TTB encoding for channel '{}' is set to {}: {}", ctx.channel().hashCode(), r.getUseNative(), ctx.channel());
                            break;
                    }
                }
                return;
            }

            final byte[] array = new byte[length-1];
            final int msgCode = in.readByte();
            in.readBytes(array);

            switch (msgCode) {
                case RiakMessageCodes.MSG_TsPutResp:
                    final RiakTsPB.TsPutResp r = RiakTsPB.TsPutResp.newBuilder().build();
                    out.add(new RiakMessage(RiakMessageCodes.MSG_TsPutResp, r.toByteArray()));
                    return;
            }

            final OtpErlangObject o;
            try {
                OtpInputStream is = new OtpInputStream(array);
                o = is.read_any();
            }catch (Exception ex){
                in.resetReaderIndex();
                in.markReaderIndex();

                // skip length header
                in.readInt();

                // read msg code
                byte code = in.readByte();

                if (isTTBMessage(code)) {
                    throw  ex;
                }

                in.resetReaderIndex();

                /**
                 * As we failed with TTB encoding, it worth to try with PB
                 */
                pbCodec.decode(null, in, out);
                return;
            }

            assert o != null;
            if (o instanceof OtpErlangTuple && ((OtpErlangTuple)o).elementAt(0) instanceof OtpErlangAtom) {
                final OtpErlangTuple t = ((OtpErlangTuple)o);
                final OtpErlangAtom resp = (OtpErlangAtom)t.elementAt(0);
                final String v = resp.atomValue();

                if ("rpberrorresp".equals(v)) {
                    final String errMsg;
                    if (t.elementAt(1) instanceof OtpErlangString) {
                        errMsg = ((OtpErlangString) t.elementAt(1)).stringValue();
                    } else if (t.elementAt(1) instanceof OtpErlangBinary) {
                        errMsg = new String(((OtpErlangBinary) t.elementAt(1)).binaryValue());
                    } else {
                        throw new IllegalStateException();
                    }

                    final OtpErlangLong errCode = (OtpErlangLong) t.elementAt(2);
                    final RiakPB.RpbErrorResp r = RiakPB.RpbErrorResp.newBuilder()
                            .setErrcode(errCode.intValue())
                            .setErrmsg(ByteString.copyFromUtf8(errMsg))
                            .build();
                    out.add(new RiakMessage(RiakMessageCodes.MSG_ErrorResp, r.toByteArray()));
                } else if ("tsgetresp".equals(v)) {
                    final RiakTsPB.TsGetResp r = RiakTsPB.TsGetResp.newBuilder()
                            .addAllColumns(ttbColumnDescriptionToPb((OtpErlangList)t.elementAt(1)))
                            .addAllRows(ttbRowsToPb((OtpErlangList)t.elementAt(2)))
                            .build();

                    out.add(new RiakMessage(RiakMessageCodes.MSG_TsGetResp, r.toByteArray()));

//                } else if ("tsqueryresp".equals(v)) {
//                    final RiakTsPB.TsQueryResp r = tsqueryrespToPB();
//                    out.add(new RiakMessage(RiakMessageCodes.MSG_TsQueryResp, r.toByteArray()));
                } else {
                    throw new IllegalStateException("Can't decode TTB response, unsupported atom '"+ v + "'");
                }
            } else {
                throw new IllegalStateException("Can't decode TTB response");
            }
        }
    }

    private static final AttributeKey<Boolean> USE_TTB = AttributeKey.valueOf("useTTB");

    private static boolean isTTBOnForChanel(Channel channel) {
        final Boolean b = channel.attr(USE_TTB).get();
        return Boolean.TRUE.equals(b);
    }

    private static boolean updateTTBUsageForChannel(Channel channel, boolean useTTB) {
        channel.attr(USE_TTB).getAndSet(Boolean.valueOf(useTTB));
        return useTTB;
    }
}
