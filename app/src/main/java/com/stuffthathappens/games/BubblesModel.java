package com.stuffthathappens.games;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.provider.Settings;
import android.util.Log;

import com.firebase.client.ChildEventListener;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.ServerValue;
import com.firebase.client.ValueEventListener;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This data model tracks bubbles on the screen.
 *
 * @see BubblesActivity
 */
public class BubblesModel implements OnCompletionListener {
    private static final String TAG = BubblesModel.class.getSimpleName();

    private final Firebase firebaseRef;

    private static final float MAX_RADIUS = 100f;

    private final List<MediaPlayer> players = new ArrayList<MediaPlayer>();
    private boolean running = false;

    public static long clockSkew = 0;


    public static class Bubble {
        private static final float INITIAL_RADIUS = 20f;
        private static final float RADIUS_CHANGE_PER_MS = .01f;

        public long getBorn() {
            return born;
        }

        public void setBorn(long born) {
            this.born = born;
        }

        public float getX() {
            return x;
        }

        public void setX(float x) {
            this.x = x;
        }

        public float getY() {
            return y;
        }

        public void setY(float y) {
            this.y = y;
        }


        public long radius() {
            long deltaMs = (System.currentTimeMillis() + clockSkew) - this.born;
            return (long) (deltaMs * RADIUS_CHANGE_PER_MS + INITIAL_RADIUS);
        }

        float x, y;
        long born;

        public Bubble() {
            this.born = System.currentTimeMillis() + clockSkew;
        }

        public Bubble(float x, float y, long born) {
            this.x = x;
            this.y = y;
            this.born = born;
        }

        public Bubble(float x, float y) {
            this.x = x;
            this.y = y;
            this.born = System.currentTimeMillis() + clockSkew;
        }

    }

    private final Map<String, Bubble> bubbles = new ConcurrentHashMap<>();

    private volatile long lastTimeMs = -1;

    public BubblesModel() {
        // Listen for clock skew changes
        Firebase clockSkewRef = new Firebase("https://dinosaurs.firebaseio.com/.info/serverTimeOffset");
        clockSkewRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                clockSkew = dataSnapshot.getValue(Long.class);
                Log.v(TAG, "Clock skew updated: " + clockSkew);
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                Log.v(TAG, "Firebase error happened: " + firebaseError);
            }
        });

        firebaseRef = new Firebase("https://dinosaurs.firebaseio.com/bubbles/");

        // Listen to changes in bubbles from other systems
        firebaseRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                // Add to local model
                bubbles.put(dataSnapshot.getKey(), dataSnapshot.getValue(Bubble.class));
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                // Update local model
                bubbles.put(dataSnapshot.getKey(), dataSnapshot.getValue(Bubble.class));
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                // Remove from local model
                bubbles.remove(dataSnapshot.getKey());
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
                // Don't really care
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                Log.v(TAG, "Firebase error happened: " + firebaseError);

            }
        });
    }

    public void onResume(Context context) {
        for (int i = 0; i < 4; i++) {
            MediaPlayer mp = MediaPlayer.create(context, R.raw.pop);
            mp.setVolume(1f, 1f);
            players.add(mp);
            try {
                mp.setLooping(false);
                mp.setOnCompletionListener(this);

                // TODO: there is a serious bug here. After a few seconds of
                // inactivity, we see this in LogCat:
                //   AudioHardwareMSM72xx Going to standby
                // then the sounds don't play until you click several more
                // times, then it starts working again

            } catch (Exception e) {
                e.printStackTrace();
                players.remove(mp);
            }
        }
        running = true;

    }

    public void onPause(Context context) {
        running = false;
        for (MediaPlayer p : players) {
            p.release();
        }
        players.clear();

    }

    public List<Bubble> getBubbles() {
        return new ArrayList<>(bubbles.values());

    }

    public void addBubble(float x, float y) {
        Firebase pushPath = firebaseRef.push();
        String id = pushPath.getKey();
        firebaseRef.child(id).setValue(new Bubble(x, y));
    }

    public void setSize(int width, int height) {
        // TODO ignore this for now...we could hide bubbles that
        // are out of bounds, for example
    }

    public void updateBubbles() {
        long curTime = System.currentTimeMillis();

        if (lastTimeMs < 0) {
            lastTimeMs = curTime;
            // this is the first reading, so don't change anything
            return;
        }

        lastTimeMs = curTime;

        MediaPlayer mp = null;

        Set<Bubble> victims = new HashSet<Bubble>();

        for (String bubbleId : bubbles.keySet()) {
            Bubble bubble = bubbles.get(bubbleId);

            if (bubble.radius() > MAX_RADIUS) {
                Log.v(TAG, "removing bubble with radius " + bubble.radius());
                victims.add(bubble);

                // Pipe the deletion through Firebase
                firebaseRef.child(bubbleId).removeValue();
            }
        }

        if (victims.size() > 0) {
            // since a bubble popped, try to get a media player
            if (!players.isEmpty()) {
                mp = players.remove(0);
            }
        }


        if (mp != null) {
            //System.out.println("**pop**");
            mp.start();
        }
    }

    public void onCompletion(MediaPlayer mp) {
        if (running) {
            mp.seekTo(0);
            //System.out.println("on completion!");
            // return the player to the pool of available instances
            players.add(mp);
        }

    }
}
