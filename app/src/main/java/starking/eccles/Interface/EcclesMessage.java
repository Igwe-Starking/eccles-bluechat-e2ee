package starking.eccles.Interface;

import androidx.annotation.NonNull;

import java.io.IOException;

import starking.eccles.bluechat.Interface.EcclesReader;
import starking.eccles.bluechat.Interface.EcclesWriter;

public final class EcclesMessage {

	public String sender;
	public transient String date;
	public String to;
	public byte[] data;
	public byte[] voice;
	public byte[] video;
	public int type;
	public int subtype;

	private static final java.util.concurrent.atomic.AtomicLong idCounter= new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Unique per logical message, including across {@link #clone()} (which copies this rather
	 * than generating a new one) - this is what actually lets a "sending" UI row find itself
	 * again once {@link EcclesMessage#send} completes.
	 * <p>
	 * Root cause this fixes: {@link starking.eccles.Surface.ChatPojo#tag} used to be set to
	 * {@code sender}, and {@link starking.eccles.Surface.ChatAdapter#findPojoWithTag} used to be
	 * looked up the same way from {@code ChatActivity#sent}/{@code #sentFailed} - but
	 * {@code sender} is only ever set on *incoming* messages (see {@code Reader#setSender}); an
	 * outgoing message's {@code sender} is always null, so every outgoing row's tag was null,
	 * and the lookup explicitly requires a non-null tag. The "sent" confirmation could
	 * therefore never find the row it was meant to update, no matter how long you waited - the
	 * status was stuck on "sending" forever. A real per-message id, present on every message
	 * regardless of direction, is what this correlation actually needs.
	 */
	public long localId= idCounter.incrementAndGet();

	/** Row id of this message in the local chat store (0 = not stored yet). Needed so a message
	 *  that was just sent/received in this session can still be deleted from the database. */
	public long storeId= 0;

	public static final short TYPE_ICON= 3;
	public static final short TYPE_CHAT= 4;
	public static final short TYPE_CALL= 5;
	public static final short TYPE_VIDEO= 6;
	public static final short TYPE_ADDRESS= 13;
	/** Sent once per connection: JSON with the sender's Bluechat name and platform. */
	public static final short TYPE_PROFILE= 14;

	public static final short SUBTYPE_TEXT= 7;
	public static final short SUBTYPE_AUDIO= 8;
	public static final short SUBTYPE_VIDEO= 9;
	public static final short SUBTYPE_CALL_ACCEPTED= 10;
	public static final short SUBTYPE_IMAGE= 11;
	public static final short SUBTYPE_CALL_ONGOING= 12;
	public static final short SUBTYPE_CALL_REJECTED=13;
	public static final short SUBTYPE_CALL_REQUEST=15;
	public static final short SUBTYPE_CALL_RINGING= 16;
	public static final short SUBTYPE_CALL_UNANSWERED= 17;
	public static final short SUBTYPE_CALL_ENDED= 18;
	public static final short SUBTYPE_CALL_CONFIG= 19;
	/** data[0] = extra clockwise quarter-turns (0-3) the receiver must add to the remote video
	 *  because the sender's active camera is mounted differently (front vs rear). */
	public static final short SUBTYPE_CAMERA_ROTATION= 20;

	public EcclesMessage(int type,int subtype){
		this.type= type;
		this.subtype= subtype;
	}

	public EcclesMessage setData(final byte[] data){
		this.data= data;
		return this;
	}

	public EcclesMessage setVoice(byte[] voice){
		this.voice= voice;
		return this;
	}

	public EcclesMessage setVideo(byte[] d){
		this.video= d;
		return this;
	}
	public EcclesMessage setDate(String d){
		this.date= d;
		return this;
	}
	public EcclesMessage setSender(String s){
		this.sender= s;
		return this;
	}
	public EcclesMessage setLocalId(long id){
		this.localId= id;
		return this;
	}

	public boolean send(EcclesWriter stream) throws IOException{
		try{
			synchronized (stream.output) {
				return stream.writeMessage(this);
			}
		}  catch(IOException ie){
			throw ie;
		} catch (Exception e){
			return false;
		}
	}

	public static EcclesMessage read(EcclesReader in) throws IOException,ClassNotFoundException {

		synchronized (in.input){
			return in.readMessage();
		}
	}

	@NonNull
	public EcclesMessage clone(){
		return new EcclesMessage(type,subtype)
				.setSender(sender).setDate(date)
				.setData(data == null ? null : data.clone())
				.setVoice(voice == null ? null : voice.clone())
				.setVideo(video == null ? null : video.clone())
				.setLocalId(localId).setStoreId(storeId);
	}

	public EcclesMessage setStoreId(long id){
		this.storeId= id;
		return this;
	}
}
