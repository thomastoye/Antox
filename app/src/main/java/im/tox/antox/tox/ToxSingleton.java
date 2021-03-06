package im.tox.antox.tox;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import im.tox.antox.callbacks.AntoxOnActionCallback;
import im.tox.antox.callbacks.AntoxOnConnectionStatusCallback;
import im.tox.antox.callbacks.AntoxOnFriendRequestCallback;
import im.tox.antox.callbacks.AntoxOnMessageCallback;
import im.tox.antox.callbacks.AntoxOnNameChangeCallback;
import im.tox.antox.callbacks.AntoxOnReadReceiptCallback;
import im.tox.antox.callbacks.AntoxOnStatusMessageCallback;
import im.tox.antox.callbacks.AntoxOnTypingChangeCallback;
import im.tox.antox.callbacks.AntoxOnUserStatusCallback;
import im.tox.antox.data.AntoxDB;
import im.tox.antox.utils.AntoxFriend;
import im.tox.antox.utils.AntoxFriendList;
import im.tox.antox.utils.Constants;
import im.tox.antox.utils.DHTNodeDetails;
import im.tox.antox.utils.DhtNode;
import im.tox.antox.utils.Friend;
import im.tox.antox.utils.FriendInfo;
import im.tox.antox.utils.FriendRequest;
import im.tox.antox.utils.Message;
import im.tox.antox.utils.Tuple;
import im.tox.jtoxcore.JTox;
import im.tox.jtoxcore.ToxException;
import im.tox.jtoxcore.ToxUserStatus;
import im.tox.jtoxcore.callbacks.CallbackHandler;
import rx.Observable;
import rx.functions.Func2;
import rx.functions.Func3;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;
import rx.subjects.PublishSubject;

import static rx.Observable.combineLatest;

public class ToxSingleton {

    private static final String TAG = "im.tox.antox.tox.ToxSingleton";
    public JTox jTox;
    private AntoxFriendList antoxFriendList;
    public CallbackHandler callbackHandler;
    public NotificationManager mNotificationManager;
    public ToxDataFile dataFile;
    public File qrFile;
    public BehaviorSubject<ArrayList<Friend>> friendListSubject;
    public BehaviorSubject<ArrayList<FriendRequest>> friendRequestSubject;
    public BehaviorSubject<HashMap> lastMessagesSubject;
    public BehaviorSubject<HashMap> unreadCountsSubject;
    public BehaviorSubject<String> activeKeySubject;
    public BehaviorSubject<Boolean> updatedMessagesSubject;
    public BehaviorSubject<Boolean> rightPaneOpenSubject;
    public rx.Observable friendInfoListSubject;
    public rx.Observable activeKeyAndIsFriendSubject;
    public Observable friendListAndRequestsSubject;
    public Observable chatActiveAndKey;

    public String activeKey; //ONLY FOR USE BY CALLBACKS
    public boolean chatActive; //ONLY FOR USE BY CALLBACKS

    public boolean isRunning = false;

    public AntoxFriend getAntoxFriend(String key) {
        return antoxFriendList.getById(key);
    }

    public void initSubjects(Context ctx) {
        friendListSubject = BehaviorSubject.create(new ArrayList<Friend>());
        friendListSubject.subscribeOn(Schedulers.io());
        rightPaneOpenSubject = BehaviorSubject.create(new Boolean(false));
        rightPaneOpenSubject.subscribeOn(Schedulers.io());
        friendRequestSubject = BehaviorSubject.create(new ArrayList<FriendRequest>());
        friendRequestSubject.subscribeOn(Schedulers.io());
        lastMessagesSubject = BehaviorSubject.create(new HashMap());
        lastMessagesSubject.subscribeOn(Schedulers.io());
        unreadCountsSubject = BehaviorSubject.create(new HashMap());
        unreadCountsSubject.subscribeOn(Schedulers.io());
        activeKeySubject = BehaviorSubject.create("");
        activeKeySubject.subscribeOn(Schedulers.io());
        updatedMessagesSubject = BehaviorSubject.create(new Boolean(true));
        updatedMessagesSubject.subscribeOn(Schedulers.io());
        friendInfoListSubject = combineLatest(friendListSubject, lastMessagesSubject, unreadCountsSubject, new Func3<ArrayList<Friend>, HashMap, HashMap, ArrayList<FriendInfo>>() {
            @Override
            public ArrayList<FriendInfo> call(ArrayList<Friend> fl, HashMap lm, HashMap uc) {
                ArrayList<FriendInfo> fi = new ArrayList<FriendInfo>();
                for (Friend f : fl) {
                    String lastMessage;
                    Timestamp lastMessageTimestamp;
                    int unreadCount;
                    if (lm.containsKey(f.friendKey)) {
                        lastMessage = (String) ((Tuple<String, Timestamp>) lm.get(f.friendKey)).x;
                        lastMessageTimestamp = (Timestamp) ((Tuple<String, Timestamp>) lm.get(f.friendKey)).y;
                    } else {
                        lastMessage = "";
                        lastMessageTimestamp = new Timestamp(0, 0, 0, 0, 0, 0, 0);
                    }
                    if (uc.containsKey(f.friendKey)) {
                        unreadCount = (Integer) uc.get(f.friendKey);
                    } else {
                        unreadCount = 0;
                    }
                    fi.add(new FriendInfo(f.icon, f.friendName, f.friendStatus, f.personalNote, f.friendKey, lastMessage, lastMessageTimestamp, unreadCount));
                }
                return fi;
            }
        });
        friendListAndRequestsSubject = combineLatest(friendInfoListSubject, friendRequestSubject, new Func2<ArrayList<FriendInfo>, ArrayList<FriendRequest>, Tuple<ArrayList<FriendInfo>, ArrayList<FriendRequest>>>() {
            @Override
            public Tuple<ArrayList<FriendInfo>, ArrayList<FriendRequest>> call(ArrayList<FriendInfo> fl, ArrayList<FriendRequest> fr) {
                return new Tuple(fl, fr);
            }
        });
        activeKeyAndIsFriendSubject = combineLatest(activeKeySubject, friendListSubject, new Func2<String, ArrayList<Friend>, Tuple<String, Boolean>>() {
            @Override
            public Tuple<String, Boolean> call(String key, ArrayList<Friend> fl) {
                boolean isFriend;
                isFriend = isKeyFriend(key, fl);
                return new Tuple<String, Boolean>(key, isFriend);
            }
        });
        chatActiveAndKey = combineLatest(rightPaneOpenSubject, activeKeySubject, new Func2<Boolean, String, Tuple<String, Boolean>>() {
            @Override
            public Tuple<String, Boolean> call(Boolean rightActive, String key) {
                return new Tuple<String, Boolean>(key, rightActive);
            }

        });
    }

    ;

    private boolean isKeyFriend(String key, ArrayList<Friend> fl) {
        for (Friend f : fl) {
            if (f.friendKey.equals(key)) {
                return true;
            }
        }
        return false;
    }

    ;

    public void updateFriendsList(Context ctx) {
        try {
            AntoxDB antoxDB = new AntoxDB(ctx);

            ArrayList<Friend> friendList = antoxDB.getFriendList();

            antoxDB.close();

            friendListSubject.onNext(friendList);
        } catch (Exception e) {
            friendListSubject.onError(e);
        }
    }
    public void clearUselessNotifications (String key) {
        if (key != null && !key.equals(""))
            mNotificationManager.cancel(getAntoxFriend(key).getFriendnumber());
    }

    public void sendUnsentMessages(Context ctx) {
            AntoxDB db = new AntoxDB(ctx);
            ArrayList<Message> unsentMessageList = db.getUnsentMessageList();
            for (int i = 0; i<unsentMessageList.size(); i++) {
                AntoxFriend friend = null;
                int id = unsentMessageList.get(i).message_id;
                boolean sendingSucceeded = true;
                try {
                    friend = getAntoxFriend(unsentMessageList.get(i).key);
                } catch (Exception e) {
                    Log.d(TAG, e.toString());
                }
                try {
                    if (friend != null) {
                        jTox.sendMessage(friend, unsentMessageList.get(i).message, id);
                    }
                } catch (ToxException e) {
                    Log.d(TAG, e.toString());
                    e.printStackTrace();
                    sendingSucceeded = false;
                }
                if (sendingSucceeded) {
                    db.updateUnsentMessage(id);
                }
            }
            db.close();
            updateMessages(ctx);
    }

    public void updateFriendRequests(Context ctx) {
        try {
            AntoxDB antoxDB = new AntoxDB(ctx);
            ArrayList<FriendRequest> friendRequest = antoxDB.getFriendRequestsList();
            antoxDB.close();
            friendRequestSubject.onNext(friendRequest);
        } catch (Exception e) {
            friendRequestSubject.onError(e);
        }
    }

    public void updateMessages(Context ctx) {
        updatedMessagesSubject.onNext(true);
        updateLastMessageMap(ctx);
        updateUnreadCountMap(ctx);
    }

    public void updateLastMessageMap(Context ctx) {
        try {
            AntoxDB antoxDB = new AntoxDB(ctx);
            HashMap map = antoxDB.getLastMessages();
            antoxDB.close();

            lastMessagesSubject.onNext(map);
        } catch (Exception e) {
            lastMessagesSubject.onError(e);
        }
    }

    public void updateUnreadCountMap(Context ctx) {
        try {
            AntoxDB antoxDB = new AntoxDB(ctx);
            HashMap map = antoxDB.getUnreadCounts();
            antoxDB.close();

            unreadCountsSubject.onNext(map);
        } catch (Exception e) {
            unreadCountsSubject.onError(e);
        }
    }

    private static volatile ToxSingleton instance = null;

    private ToxSingleton() {
    }

    public void initTox(Context ctx) {
        antoxFriendList = new AntoxFriendList();
        callbackHandler = new CallbackHandler(antoxFriendList);

        qrFile = ctx.getFileStreamPath("userkey_qr.png");
        dataFile = new ToxDataFile(ctx);

        /* Choose appropriate constructor depending on if data file exists */
        if (!dataFile.doesFileExist()) {
            try {
                jTox = new JTox(antoxFriendList, callbackHandler);
                /* Save data file */
                dataFile.saveFile(jTox.save());
                /* Save users public key to settings */
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(ctx);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("tox_id", jTox.getAddress());
                editor.commit();
            } catch (ToxException e) {
                e.printStackTrace();
            }
        } else {
            try {
                jTox = new JTox(dataFile.loadFile(), antoxFriendList, callbackHandler);
            } catch (ToxException e) {
                e.printStackTrace();
            }
        }

        try {
            System.load("/data/data/im.tox.antox/lib/libsodium.so");
            System.load("/data/data/im.tox.antox/lib/libtoxcore.so");
        } catch (Exception e) {
            Log.d(TAG, "Failed System.load()");
            e.printStackTrace();
        }

        /* If the service wasn't running then we wouldn't have gotten callbacks for a user
         *  going offline so default everyone to offline and just wait for callbacks.
        */
        AntoxDB db = new AntoxDB(ctx);
        db.setAllOffline();

        /* Populate tox friends list with saved friends in database */
        ArrayList<Friend> friends = db.getFriendList();
        db.close();

        if (friends.size() > 0) {
            for (int i = 0; i < friends.size(); i++) {
                try {
                    jTox.confirmRequest(friends.get(i).friendKey);
                } catch (Exception e) {
                }
            }
        }

        /* Instansiate and register callback classes */
        AntoxOnMessageCallback antoxOnMessageCallback = new AntoxOnMessageCallback(ctx);
        AntoxOnFriendRequestCallback antoxOnFriendRequestCallback = new AntoxOnFriendRequestCallback(ctx);
        AntoxOnActionCallback antoxOnActionCallback = new AntoxOnActionCallback(ctx);
        AntoxOnConnectionStatusCallback antoxOnConnectionStatusCallback = new AntoxOnConnectionStatusCallback(ctx);
        AntoxOnNameChangeCallback antoxOnNameChangeCallback = new AntoxOnNameChangeCallback(ctx);
        AntoxOnReadReceiptCallback antoxOnReadReceiptCallback = new AntoxOnReadReceiptCallback(ctx);
        AntoxOnStatusMessageCallback antoxOnStatusMessageCallback = new AntoxOnStatusMessageCallback(ctx);
        AntoxOnUserStatusCallback antoxOnUserStatusCallback = new AntoxOnUserStatusCallback(ctx);
        AntoxOnTypingChangeCallback antoxOnTypingChangeCallback = new AntoxOnTypingChangeCallback(ctx);

        callbackHandler.registerOnMessageCallback(antoxOnMessageCallback);
        callbackHandler.registerOnFriendRequestCallback(antoxOnFriendRequestCallback);
        callbackHandler.registerOnActionCallback(antoxOnActionCallback);
        callbackHandler.registerOnConnectionStatusCallback(antoxOnConnectionStatusCallback);
        callbackHandler.registerOnNameChangeCallback(antoxOnNameChangeCallback);
        callbackHandler.registerOnReadReceiptCallback(antoxOnReadReceiptCallback);
        callbackHandler.registerOnStatusMessageCallback(antoxOnStatusMessageCallback);
        callbackHandler.registerOnUserStatusCallback(antoxOnUserStatusCallback);
        callbackHandler.registerOnTypingChangeCallback(antoxOnTypingChangeCallback);

        /* Load user details */
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(ctx);
            jTox.setName(preferences.getString("nickname", ""));
            jTox.setStatusMessage(preferences.getString("status_message", ""));
            ToxUserStatus newStatus = ToxUserStatus.TOX_USERSTATUS_NONE;
            String newStatusString = preferences.getString("status", "");
            if (newStatusString.equals("2"))
                newStatus = ToxUserStatus.TOX_USERSTATUS_AWAY;
            if (newStatusString.equals("3"))
                newStatus = ToxUserStatus.TOX_USERSTATUS_BUSY;
            jTox.setUserStatus(newStatus);
        } catch (ToxException e) {

        }

        /* Check if connected to the Internet */
        ConnectivityManager connMgr = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();

        /* If connected to internet, download nodes */
        if (networkInfo != null && networkInfo.isConnected()) {
            try {
                new DHTNodeDetails(ctx).execute().get(); // Make sure finished getting nodes first
                /* Try and bootstrap to online nodes*/
                while (DhtNode.connected == false && networkInfo.isConnected()) {
                    try {
                        if (DhtNode.ipv4.size() > 0) {
                            try {
                                jTox.bootstrap(DhtNode.ipv4.get(DhtNode.counter),
                                        Integer.parseInt(DhtNode.port.get(DhtNode.counter)), DhtNode.key.get(DhtNode.counter));
                            } catch (ToxException e) {

                            }

                            Log.d(TAG, "Connected to node: " + DhtNode.owner.get(DhtNode.counter));
                            DhtNode.connected = true;
                        }
                    } catch (UnknownHostException e) {
                        DhtNode.counter = DhtNode.counter >= DhtNode.ipv4.size() ? 0 : DhtNode.counter++;
                    }
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static ToxSingleton getInstance() {
        /* Double-checked locking */
        if(instance == null) {
            synchronized (ToxSingleton.class) {
                if(instance == null) {
                    instance = new ToxSingleton();
                }
            }
        }

        return instance;
    }
}
