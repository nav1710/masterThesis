package org.lifesocial.plugin.collaborativeplugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import javax.crypto.SecretKey;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import org.lifesocial.plugin.collaborativeplugin.api.CollaborativeNotificationObserver;
import org.lifesocial.plugin.collaborativeplugin.api.ICollaborativePlugin;
import org.lifesocial.plugin.collaborativeplugin.util.PluginPropertiesContainer;
import org.lifesocial.plugin.notificationplugin.INotificationPlugin;
import org.lifesocial.plugin.notificationplugin.INotificationPluginListener;
import org.lifesocial.plugin.notificationplugin.NotificationPlugin;
import org.lifesocial.plugin.notificationplugin.data.NotificationPayload;
import org.lifesocial.utilities.ConfigLoader;
import org.lifesocial.utilities.PropertiesContainer;
import org.lifesocial.core.commoninterfaces.Plugin;
import org.lifesocial.core.data.collaborative.CollaborativeKeysContainer;
import org.lifesocial.core.data.collaborative.CollaborativeMessageImpl;
import org.lifesocial.core.data.collaborative.CollaborativeMsgContainer;
import org.lifesocial.core.data.collaborative.CollaborativeSortDocumentMsg;
import org.lifesocial.core.data.collaborative.DocumentChannelMsgPayload;
import org.lifesocial.core.data.collaborative.CollaborativeMessageImplPayload;
import org.lifesocial.core.data.collaborative.CollaborativeKeys;
import org.lifesocial.core.data.collaborative.CollaborativeKeysPayload;
import org.lifesocial.core.data.common.SecureSharedItem;
import org.lifesocial.core.data.common.SecureSharedItemPayload;
import org.lifesocial.core.data.impl.HeaderImpl;
import org.lifesocial.core.data.impl.SharedItem;
import org.lifesocial.core.data.impl.StorageKeyImpl;
import org.lifesocial.core.data.interfaces.Header;
import org.lifesocial.core.data.interfaces.Payload;
import org.lifesocial.core.data.interfaces.StorageKey;
import org.lifesocial.core.data.utils.HashKeyBuilder;
import org.lifesocial.friendsplugin.api.IFriendsPlugin;
import org.lifesocial.logger.api.ILogService;
import org.lifesocial.loginplugin.api.ILoginPlugin;
import org.lifesocial.loginplugin.component.ILoginStatusChangedListener;
import org.lifesocial.loginplugin.component.LoginComponent;
import org.lifesocial.messagedispatcher.api.IMessageDispatcher;
import org.lifesocial.messagedispatcher.api.MessageListener;
import org.lifesocial.middlewarestorage.informationcache.caches.PluginMsgsCacheChecker;
import org.lifesocial.middlewarestorage.informationcache.requests.SharedItemRequest;
import org.lifesocial.middlewarestorage.storagedispatcher.api.IStorageDispatcher;
import com.p2pframework.Framework;
import com.p2pframework.ReceiveCallback;
import com.p2pframework.datacollection.social.SocialDataCollection;
import com.p2pframework.datacollection.social.SocialDataCollectionImpl;
import com.p2pframework.message.GenericMessage;
import com.p2pframework.message.SubscriptionFailedException;
import com.p2pframework.message.TopicChannel;
import com.p2pframework.security.AsymmetricKey;
import com.p2pframework.security.KeyException;
import com.p2pframework.security.SymmetricKey;
import com.p2pframework.security.ellipticcurves.AESSymmetricKey;
import org.osgi.service.component.ComponentContext;

/**
 * @author Navendu Barua
 */

public class CollaborativePlugin implements ICollaborativePlugin,
		INotificationPluginListener, Plugin, ILoginStatusChangedListener,
		ReceiveCallback<String, com.p2pframework.message.Message>,
		MessageListener {

	private CollaborativeKeys collabKeys;
	private final Object contentLock = new Object();

	private static final int MESSAGES_AMOUNT = 20;

	private ILoginPlugin login = null;
	private IFriendsPlugin friends = null;
	private IMessageDispatcher messageDispatcher = null;
	private IStorageDispatcher storageDispatcher = null;
	private HashMap<String, TopicChannel> topicChannelMap;
	private INotificationPlugin notificationPlugin;
	private ILogService logger = null;
	private Framework framework;
	private static final String NOTIFICATION_DOCUMENT_INVITATION = "collaborativeinvitation";
	private static final String NOTIFICATION_DOCUMENT_DECLINE_INVITATION = "collaborativeinvitationdecline";

	final String className = "PLUGIN_COLLABORATIVE";

	private ArrayList<CollaborativeNotificationObserver> notificationObservers = new ArrayList<CollaborativeNotificationObserver>();

	CollaborativeMessageImplPayload m;
	
	SocialDataCollection sdc = new SocialDataCollectionImpl();
	int c = ConfigLoader.getIntConfigProperty("socialMetricCollection");
	
	private long lastMsgTime = -1;
	private long lastStoredTime = -1;
	private PluginMsgsCacheChecker checker;
	
	
	
	private Thread storeThread = new Thread(){
		public void run(){
			
			while(true){
				while(login == null || !login.isLoggedIn()){
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				
				//only store when there is something to store (lastMsgTime != -1)
				//only store when last send or retrieved msg is longer than 20s ago
				//store when there is something to store and more than 60s are gone after last storage.
				if(lastMsgTime != -1 && (System.currentTimeMillis() - lastMsgTime > 20000 || System.currentTimeMillis() - lastStoredTime > 60000) ){
					if (storageDispatcher != null) {
						if (collabKeys != null){
							if(framework != null){
								//encrypt History so that only this user is able to decrypt it.
								AsymmetricKey userKey = framework.getNetwork().getUserKey();
								SecureSharedItemPayload payload = new SecureSharedItemPayload(collabKeys, userKey.getPublic(), userKey.getPrivate());
								final SecureSharedItem shist = new SecureSharedItem(collabKeys.getStorageKey(), collabKeys.getHeader(), payload);
								
								storageDispatcher.storeSharedItem(shist, className);
								lastMsgTime = -1;
								lastStoredTime = System.currentTimeMillis();
							}
						}
					}
				}
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	};

	protected void activate(ComponentContext context) {
		
		new Thread(){
			public void run(){
				while(login == null || !login.isLoggedIn()){
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				collabKeys = getCollabKeys();
			}
		}.start();
		
		if(!storeThread.isAlive()){
			storeThread.setDaemon(true);
			storeThread.setName("CollaborativeKeysStoreThread");
			storeThread.start();
		}
		
		this.checker = new PluginMsgsCacheChecker(this.getPluginId(), this);
		this.checker.start();
		topicChannelMap = new HashMap<String, TopicChannel>();

	}

	protected void deactivate(ComponentContext context) {
		if (this.storageDispatcher != null) {
			if (this.collabKeys != null){

				if(framework != null){
					//encrypt History so that only this user is able to decrypt it.
					AsymmetricKey userKey = framework.getNetwork().getUserKey();
					SecureSharedItemPayload payload = new SecureSharedItemPayload(collabKeys, userKey.getPublic(), userKey.getPrivate());
					final SecureSharedItem shist = new SecureSharedItem(collabKeys.getStorageKey(), collabKeys.getHeader(), payload);
					
					this.storageDispatcher.storeSharedItem(shist, className);
				}
			}
		} else
			this.logger
					.warning(
							this.getClass(),
							PluginPropertiesContainer.CollaborativeErrorStoringCollaborativeKeysMsg);
		try {
			if (this.checker.isAlive())
				this.checker.shutdown();
		} catch (Exception e) {
			this.logger.error(this.getClass(),
					PluginPropertiesContainer.ExceptionShuttingDownCheckerMsg,
					e);
		}
		return;
	}
	
	public CollaborativeMessageImpl sendFigure(float fromX, float fromY, float toX,
	        float toY, final String username, String toolType) {
		String myUsername = this.login.getUsername();
		if (myUsername != null) {
			if (this.collabKeys == null) {
				this.getCollabKeys();
			}

			final CollaborativeMessageImpl msg = new CollaborativeMessageImpl(
					new StorageKeyImpl(HashKeyBuilder.getRandomString()),
					new HeaderImpl(PluginPropertiesContainer.CollaborativeMsg,
							this.getPluginId(),
							PluginPropertiesContainer.CollaborativeMsg),
					new CollaborativeMessageImplPayload(fromX, fromY, toX, toY,
							myUsername, username, toolType));
			this.getCollabKeys().addDocumentMsg(username, msg.getPayload());
			new Thread() {
				public void run() {
					messageDispatcher.sendMessage(username, msg, className);
				}
			}.start();
			sdc.listen(c, "NUMBEROFCOLLABORATIVEMESSAGESSENT", className, 1,
					"sum");

			lastMsgTime = System.currentTimeMillis();

			return msg;
		}

		this.logger.warning(this.getClass(),
				PluginPropertiesContainer.NotLoggedInMsg);
		return null;

	}

	/**
	 * Create a signalling msg
	 * 
	 * @param documentID
	 * @param type
	 * @return
	 */
	public int publishDocumentSignallingMessages(String docuuid, String type) {
		String myUserName = this.framework.getNetwork().getUsername();
		if (myUserName != null && topicChannelMap.get(docuuid) != null) {

			DocumentChannelMsgPayload payload = new DocumentChannelMsgPayload(
					"", myUserName, type);
			topicChannelMap.get(docuuid).publish(
					new GenericMessage<DocumentChannelMsgPayload>(payload));
		}
		return PluginPropertiesContainer.CollaborativeDocumentSentSuccess;
	}

	private final Object collabKeysLock = new Object();

	

	/**
	 * Builds a storage key for a Collaborative Keys object.
	 * 
	 * @param usrname - user name of the owner
	 * @return a {@link StorageKey} object
	 */
	private StorageKey buildCollaborativeKey(String usrname) {
		StorageKeyImpl keyimpl = new StorageKeyImpl(usrname
				+ PluginPropertiesContainer.CollaborativeKeysItem
				+ PluginPropertiesContainer.PluginID);
		return keyimpl;
	}

	/**
	 * Create or subscribe the Collaborative channel Called when a document is
	 * created or joined.
	 * 
	 * @param docuuid
	 */
	private void subscribeDocumentChannel(String docuuid, SecretKey key) {

		if (key == null || docuuid == null) {
			return;
		}

		if (!topicChannelMap.containsKey(docuuid)) {
			topicChannelMap.put(docuuid, this.framework.getMessageDispatcher()
					.createTopicChannel(docuuid, key));
		}
		try {
			topicChannelMap.get(docuuid).subscribe(this);
		} catch (SubscriptionFailedException e) {
			e.printStackTrace();
		}
	}
	
	/**
	* send Invitation to users or list of users
	* 
	* @param usernames
	*/
	public String sendInvitation(List<String> usernames) {
		if(this.login==null){
			System.out.println("null");
		}
		String myUsername = null;
		myUsername = this.framework.getNetwork().getUsername();
		usernames = new ArrayList<String>(usernames);
		if (myUsername != null) {
			if (this.collabKeys == null) {
				this.getCollabKeys();
			}
			String channeluuid = "NavB"; //channel Id
			SymmetricKey sym = null;
			try {
				sym = new AESSymmetricKey();
				sym.createKey();
			} catch (KeyException e) {
				e.printStackTrace();
				sym = null;
			}

			if (sym == null) {
				return null;
			}
			this.getCollabKeys().getPayload().setSymmetricKeyForCollabDocument(
					channeluuid, sym.getKey());

			this.getCollabKeys().getPayload()
					.addUserToDocument(channeluuid, myUsername);
			this.getCollabKeys().getPayload()
					.createCollabDocumentIfNotExist(channeluuid);

			for (String user : usernames) {
				user = user.trim().toLowerCase();

				subscribeDocumentChannel(channeluuid, sym.getKey());
				publishDocumentSignallingMessages(channeluuid,
						DocumentChannelMsgPayload.TYPE_ENTERED);

				HashMap<String, Object> data = new HashMap<String, Object>();
				data.put("users", new ArrayList<String>(usernames));
				data.put("channeluuid", channeluuid);
				data.put("key", sym.getKey());

				this.notificationPlugin.sendNotification(user, getPluginId(),
						data, NotificationPlugin.NOTIFICATION_KIND_NORMAL,
						NOTIFICATION_DOCUMENT_INVITATION);
			}

			lastMsgTime = System.currentTimeMillis();

			return channeluuid;
		}

		this.logger.warning(this.getClass(),
				PluginPropertiesContainer.NotLoggedInMsg);
		return null;
	}	

	public void receiveFigure(float fromX, float fromY, float toX, float toY,
			String username, String toolType) {
		// TODO Auto-generated method stub
	}
	
	/**
	* add users to allowed list of users for the channel with uuid.
	* 
	* @param docuuid
	*/

	public void addUsersToCollaborativeDocument(String uuid,
			List<String> usernames) {
		String myUsername = this.framework.getNetwork().getUsername();
		usernames = new ArrayList<String>(usernames);
		if (myUsername != null) {
			if (this.collabKeys == null) {
				this.getCollabKeys();
			}
			
			//key is being set as null
			SecretKey key = this.collabKeys.getPayload().getSymmetricKey(uuid);

			if (key == null) {
				return;
			}
			ArrayList<String> dataUserList;
			for (String user : usernames) {
				user = user.trim().toLowerCase();

				HashMap<String, Object> data = new HashMap<String, Object>();
				dataUserList = new ArrayList<String>(usernames);
				dataUserList.addAll(usernames);

				data.put("users", dataUserList);
				data.put("channeluuid", uuid);
				data.put("key", key);

				this.notificationPlugin.sendNotification(user, getPluginId(),
						data, NotificationPlugin.NOTIFICATION_KIND_NORMAL,
						NOTIFICATION_DOCUMENT_INVITATION);
			}
			lastMsgTime = System.currentTimeMillis();
		}

	}

	public void handleMessage(rice.p2p.commonapi.Message msg) {
		if (msg instanceof CollaborativeMessageImpl) {
			final CollaborativeMessageImpl message = (CollaborativeMessageImpl) msg;

			if (this.collabKeys == null) {
				this.getCollabKeys();
			}
			if (message != null) {
				// one to one only with friends
				boolean found = false;
				if (this.friends != null) {
					for (String username : this.friends.getMyOwnList()
							.getPayload().getUsernames()) {
						if (username.equals(message.getPayload().getFromUser())) {
							found = true;
						}
					}
				}
				if (!found) {
					// only msgs from friends are accepted
					return;
				}

				this.collabKeys.addDocumentMsg(message.getPayload()
						.getFromUser(), message.getPayload());

				lastMsgTime = System.currentTimeMillis();

				lastMsgTime = System.currentTimeMillis();

				for (int i = 0; i < notificationObservers.size(); i++) {
					notificationObservers.get(i).notifyNewMessage(
							message.getPayload(),
							message.getPayload().getFromUser());
				}
			}
		}
	}
	
	public CollaborativeMsgContainer getDocumentMessages(String username,
			int messagesOffset) {
		CollaborativeKeys keys = this.getCollabKeys();

		if (username == null) {
			Collection<String> chats = keys.getPayload().getCollabKeysSet();
			if (chats.size() == 0) {
				return new CollaborativeMsgContainer(false,
						new TreeSet<CollaborativeMessageImplPayload>(
								new CollaborativeSortDocumentMsg(true)));
			} else {
				this.getDocumentMessages(chats.iterator().next(),
						messagesOffset);
			}
		}
		TreeSet<CollaborativeMessageImplPayload> messages = keys
				.getUserHistory(username);
		sdc.listen(c, "NUMBEROFVIEWCHATHISTORY", className, 1, "sum");

		if (messages == null) {
			return new CollaborativeMsgContainer(false,
					new TreeSet<CollaborativeMessageImplPayload>(
							new CollaborativeSortDocumentMsg(true)));
		} else {

			TreeSet<CollaborativeMessageImplPayload> messagesSubSet = new TreeSet<CollaborativeMessageImplPayload>(
					new CollaborativeSortDocumentMsg(true));

			int pointer = -1;
			for (CollaborativeMessageImplPayload msg : messages) {
				pointer++;
				if (pointer < messagesOffset) {
					continue;
				}
				messagesSubSet.add(msg);
				if (messagesSubSet.size() == MESSAGES_AMOUNT) {
					if (messages.size() - 1 > pointer) {
						return new CollaborativeMsgContainer(true,
								messagesSubSet);
					} else {
						return new CollaborativeMsgContainer(false,
								messagesSubSet);
					}
				}
			}
			return new CollaborativeMsgContainer(false, messagesSubSet);
		}
	}

	public void registerCollaborativeNotificationObserver(
			CollaborativeNotificationObserver obs) {
		notificationObservers.add(obs);
	}
	
	/**
	* fetch all the collaborative documents for the user.
	*/
	public Collection<CollaborativeKeysContainer> getCollaborativeDocuments() {
		final CollaborativeKeys collabKeys = this.getCollabKeys();
		
		List<CollaborativeKeysContainer> result = new ArrayList<CollaborativeKeysContainer>();
		
		for(String name : collabKeys.getPayload().getCollabKeysSet()){
			if(collabKeys.getPayload().getType(name).equals(CollaborativeKeysContainer.TYPE_DOCUMENT)){
				result.add(new CollaborativeKeysContainer(name, this.getDocumentName(name), collabKeys.getPayload().getType(name)));
			}else{
				ArrayList<String> names = new ArrayList<String>(1);
				names.add(name);
				result.add(new CollaborativeKeysContainer(name, names, collabKeys.getPayload().getType(name)));
			}
		}
		if(result.size() > 1){
			Collections.sort(result, new Comparator<CollaborativeKeysContainer>() {
				@Override
				public int compare(CollaborativeKeysContainer o1, CollaborativeKeysContainer o2) {
					Date date1 = null;
					Date date2 = null;
					
					if(collabKeys.getUserHistory(o1.getUniqueName()).size() == 0){
						date1 = collabKeys.getPayload().getDocumentCreationDate(o1.getUniqueName());
					}else{
						date1 = collabKeys.getUserHistory(o1.getUniqueName()).last().getIssueDate();
					}
					if(collabKeys.getUserHistory(o2.getUniqueName()).size() == 0){
						date2 = collabKeys.getPayload().getDocumentCreationDate(o2.getUniqueName());
					}else{
						date2 = collabKeys.getUserHistory(o2.getUniqueName()).last().getIssueDate();
					}
					return date1.compareTo(date2) * -1;
				}
			});
		}
		
		return result;
	}

	public CollaborativeKeys getCollabKeys() {
		synchronized (this.collabKeysLock) {
			if (this.collabKeys == null) {
				if(this.framework.getNetwork().getUsername()!=null){
					StorageKey key = this.buildCollaborativeKey( this.framework.getNetwork().getUsername());
					SharedItemRequest request = new SharedItemRequest(key);
					SharedItem collabKeysItem = request.execute(5000);

					if (collabKeysItem != null
							&& collabKeysItem instanceof SecureSharedItem) {
						// decrypt Collaborative Keys
						AsymmetricKey userKey = framework.getNetwork().getUserKey();
						final SecureSharedItem shist = (SecureSharedItem) collabKeysItem;
						collabKeysItem = shist.getPayload().getSharedItem(
								userKey.getPublic(), userKey.getPrivate());

						// rejoin all topicchats.
						CollaborativeKeysPayload payload = ((CollaborativeKeys) collabKeysItem)
								.getPayload();
						Collection<String> keys = payload.getCollabKeysSet();

						SecretKey symkey;
						String type;
						for (String eachkey : keys) {
							type = payload.getType(eachkey);
							if (type.equals(CollaborativeKeysContainer.TYPE_DOCUMENT)) {

								symkey = payload.getSymmetricKey(eachkey);

								this.subscribeDocumentChannel(eachkey, symkey);
							}
						}

						return (CollaborativeKeys) collabKeysItem;
					}

					// History does not exist. So we create it
					Header header = new HeaderImpl(
							PluginPropertiesContainer.CollaborativeKeys,
							this.getPluginId(),
							PluginPropertiesContainer.CollaborativeKeys);

					CollaborativeKeys hist = new CollaborativeKeys(key, header,
							new CollaborativeKeysPayload());

					// encrypt History so that only this user is able to decrypt
					// it.
					AsymmetricKey userKey = framework.getNetwork().getUserKey();
					SecureSharedItemPayload payload = new SecureSharedItemPayload(
							hist, userKey.getPublic(), userKey.getPrivate());
					final SecureSharedItem shist = new SecureSharedItem(key,
							header, payload);

					this.collabKeys = hist;
					new Thread() {
						public void run() {
							storageDispatcher.storeSharedItem(shist, className);
							//error is here NullpointerException
						}
					}.start();
				} else
					this.logger.warning(this.getClass(),
							PropertiesContainer.NotLoggedInMsg);
				return this.collabKeys;

			} else {
				return this.collabKeys;
			}
		}
	}

	/**
	 * Receiving a TopicChannel Msg.
	 * @param uuid
	 * @param data
	 */
	@SuppressWarnings("unchecked")
	
	public void receive(final String uuid, com.p2pframework.message.Message data) {
		if (data instanceof GenericMessage) {
			GenericMessage<Payload> message = (GenericMessage<Payload>) data;
			Payload msg = message.getStore();
			if (msg != null) {
				if (msg instanceof CollaborativeMessageImplPayload) {
					// normal messages
					// filter friends and blocklisted users
					CollaborativeMessageImplPayload payload = (CollaborativeMessageImplPayload) msg;
					if (this.friends != null) {
						for (String username : this.friends.getMyOwnBlockList()
								.getPayload().getUsernames()) {
							if (username.equals(payload.getFromUser())) {
								// we ignore blocked users even in Collab Work.
								return;
							}
						}
					}
					lastMsgTime = System.currentTimeMillis();

					lastMsgTime = System.currentTimeMillis();
					
					for (int i = 0; i<notificationObservers.size(); i++){
						notificationObservers.get(i).notifyNewMessage(payload, uuid);						
					}
				} else if (msg instanceof DocumentChannelMsgPayload) {
					DocumentChannelMsgPayload signallingmsg = ((DocumentChannelMsgPayload) msg);
					switch (signallingmsg.getType()) {
					case DocumentChannelMsgPayload.TYPE_ENTERED:
						new Thread() {
							public void run() {
								publishDocumentSignallingMessages(uuid,
										DocumentChannelMsgPayload.TYPE_NOTIFY);
							}
						}.start();
						break;
					case DocumentChannelMsgPayload.TYPE_LEFT:
						this.getCollabKeys()
								.getPayload()
								.removeUserFromDocument(uuid,
										signallingmsg.getFrom());
						break;
					case DocumentChannelMsgPayload.TYPE_NOTIFY:
						this.getCollabKeys()
								.getPayload()
								.addUserToDocument(uuid,
										signallingmsg.getFrom());
						break;
					}
				}
			}
		}
	}
	@Override
	public void loginStatusChanged() {
		if(this.login != null && this.login.getLoginStatus() == LoginComponent.STATUS_LOGGED_OUT){
			//we log out so destroy all data.
			this.collabKeys = null;
			this.topicChannelMap = new HashMap<String, TopicChannel>();
		}else if(this.collabKeys == null){
			//we logged out and now logged in again. So set the history as fast as possible
			new Thread(){
				public void run(){
					while(login == null || !login.isLoggedIn()){
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					collabKeys = getCollabKeys();
				}
			}.start();
		}
	}

	public String getPluginId() {
		// TODO Auto-generated method stub
		return PluginPropertiesContainer.PluginID;
	}

	public void pluginMsgsAvailable() {
		// TODO Auto-generated method stub

	}

	public void printPluginId() {
		// TODO Auto-generated method stub
		this.logger.info("Plugin ID: " + this.getPluginId());
	}

	@Deprecated
	public void terminate() {
		//encrypt History so that only this user is able to decrypt it.
				AsymmetricKey userKey = framework.getNetwork().getUserKey();
				SecureSharedItemPayload payload = new SecureSharedItemPayload(collabKeys, userKey.getPublic(), userKey.getPrivate());
				final SecureSharedItem shist = new SecureSharedItem(collabKeys.getStorageKey(), collabKeys.getHeader(), payload);
				
				this.storageDispatcher.storeSharedItem(shist, className);
				try {
					if (this.checker.isAlive())
						this.checker.shutdown();
				} catch (Exception e) {
					this.logger.error(this.getClass(),
							PluginPropertiesContainer.ExceptionShuttingDownCheckerMsg,
							e);
				}
				return;
	}

	@Deprecated
	public void init() {
		this.collabKeys = null;

		this.checker = new PluginMsgsCacheChecker(this.getPluginId(), this);
		this.checker.start();
	}

	@Override
	public NotificationPayload confirmArrivedNotification(
			NotificationPayload payload) {
		if(NOTIFICATION_DOCUMENT_INVITATION.equals(payload.getType())){
			
			Object key = payload.getData().get("key");
			
			if(key != null && key instanceof SecretKey){
				
				if(this.topicChannelMap.get(payload.getData().get("channeluuid")) != null){
					return null; // we are already in this topicdocument. This is important for security as well. Otherwise someone could overwrite our sym key
				}
				
				CollaborativeKeys collabKeys = this.getCollabKeys();
				collabKeys.getPayload().setSymmetricKeyForCollabDocument(payload.getData().get("channeluuid").toString(), (SecretKey)key);
				
				collabKeys.getPayload().addUserToDocument(payload.getData().get("channeluuid").toString(), payload.getSenderUserName());
								
				//we override it here. We do not need it anymore.
				payload.getData().put("key", null);
				
				return payload;
			}else{
				return null;
			}
		}else if(NOTIFICATION_DOCUMENT_DECLINE_INVITATION.equals(payload.getType())){
			return payload;
		}
		return null;
	}

	public void createBlankDocument(List<String> usernames) {
		// TODO Auto-generated method stub
	
	}

	@Override
	public CollaborativeMessageImpl sendMsg(String lineNumber,
			String rowNumber, String content, String fromUser, String toUser) {
		// TODO Auto-generated method stub
		return null;
	}
	
	public boolean msgAvailable() {
		return false;
	}

	public void checkForUndeliveredMsgs() {
		// TODO Auto-generated method stub

	}

	@Override
	public void declineCollaborativeRequest(String docuuid, String username) {
		CollaborativeKeys collabKeys = this.getCollabKeys();
		
		collabKeys.getPayload().removeDocument(docuuid);

		this.notificationPlugin.sendNotification(username, getPluginId(), null, NotificationPlugin.NOTIFICATION_KIND_NORMAL, NOTIFICATION_DOCUMENT_DECLINE_INVITATION);
	}

	@Override
	public void acceptCollaborativeRequest(String docuuid) {
		if(docuuid == null){
			return;
		}
		
		CollaborativeKeys collabKeys = this.getCollabKeys();
		this.getCollabKeys().getPayload().addUserToDocument(docuuid, framework.getNetwork().getUsername());
		this.getCollabKeys().getPayload().createCollabDocumentIfNotExist(docuuid);
		
		this.subscribeDocumentChannel(docuuid, collabKeys.getPayload().getSymmetricKey(docuuid));
		publishDocumentSignallingMessages(docuuid, DocumentChannelMsgPayload.TYPE_ENTERED);
		
		lastMsgTime = System.currentTimeMillis();
	}

	@Override
	public int unSubscribeCollaborativeChannel(String docuuid) {
		return 0;
	}
	
	public synchronized void setMessageDispatcher(IMessageDispatcher md) {
		this.messageDispatcher = md;
		md.registerMessageListener(this, this.getPluginId());
	}

	public synchronized void unsetMessageDispatcher(IMessageDispatcher md) {
		this.messageDispatcher = null;
	}

	public synchronized void setLogin(ILoginPlugin loginc) {
		this.login = loginc;
		this.login.addLoginStatusChangedListener(this);
	}

	public synchronized void unsetLogin(ILoginPlugin loginc) {
		this.login = null;
	}

	public synchronized void setFriendsPlugin(IFriendsPlugin friends) {
		this.friends = friends;
	}

	public synchronized void unsetFriendsPlugin(IFriendsPlugin friends) {
		this.friends = null;
	}

	public synchronized void setNotificationPlugin(INotificationPlugin plugin) {
		this.notificationPlugin = plugin;
		notificationPlugin.addNotificationPluginListener(
				PluginPropertiesContainer.PluginID, this);
	}

	public synchronized void unsetNotificationPlugin(INotificationPlugin plugin) {
		this.notificationPlugin = null;
	}

	public synchronized void setSD(IStorageDispatcher sd) {
		this.storageDispatcher = sd;
	}

	public synchronized void unsetSD(IStorageDispatcher sd) {
		this.storageDispatcher = null;
	}

	public synchronized void setLogService(ILogService logService) {
		this.logger = logService;
	}

	public synchronized void unsetLogService(ILogService logService) {
		this.logger = null;
	}

	public synchronized void setFramework(Framework framework) {
		this.framework = framework;
	}

	public synchronized void unsetFramework(Framework framework) {
		this.framework = null;
	}

	public List<String> getDocumentName(String uuid) {		
		List<String> documentName = this.getCollabKeys().getPayload()
				.getDocumentName(uuid);

		if (documentName == null) {
			documentName = new ArrayList<String>();
			documentName.add( this.framework.getNetwork().getUsername());
		}
		return documentName;
	}

	public CollaborativeMessageImplPayload publishDocumentMessages(
			String docuuid, float fromX, float fromY, float toX, float toY,
			String toolType) {
		String myUserName = this.framework.getNetwork().getUsername();
		if (myUserName != null && topicChannelMap.get(docuuid) != null) {
			CollaborativeMessageImplPayload payload = new CollaborativeMessageImplPayload(
					fromX, fromY, toX, toY, myUserName, "", toolType);
			getCollabKeys().getPayload().addDocumentMsg(docuuid, payload);

			topicChannelMap
					.get(docuuid)
					.publish(
							new GenericMessage<CollaborativeMessageImplPayload>(
									payload));

			sdc.listen(c, "NUMBEROFCHATMESSAGESSENT", className, 1, "sum");
			lastMsgTime = System.currentTimeMillis();
			return payload;
		}
		return null;
	}
}