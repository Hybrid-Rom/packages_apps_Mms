/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mms.ui;

import com.android.mms.R;
import com.android.mms.data.Conversation;
import com.android.mms.util.ContactInfoCache;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;

import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * The back-end data adapter for ConversationList.
 */
//TODO: This should be public class ConversationListAdapter extends ArrayAdapter<Conversation>
public class ConversationListAdapter extends CursorAdapter {
    private static final String TAG = "ConversationListAdapter";
    private static final boolean LOCAL_LOGV = false;

    private final LayoutInflater mFactory;

    // Cache of space-separated recipient ids of a thread to the final
    // display version.

    // TODO: if you rename a contact or something, it'll cache the old
    // name (or raw number) forever in here, never listening to
    // changes from the contacts provider.  We should instead move away
    // towards using only the CachingNameStore, which does respect
    // contacts provider updates.
    private final Map<String, String> mThreadDisplayFrom;

    // For async loading of display names.
    private final ScheduledThreadPoolExecutor mAsyncLoader;
    private final Stack<Runnable> mThingsToLoad = new Stack<Runnable>();
    // We execute things in LIFO order, so as users scroll around during loading,
    // they get the most recently-requested item.
    private final Runnable mPopStackRunnable = new Runnable() {
            public void run() {
                Runnable r = null;
                synchronized (mThingsToLoad) {
                    if (!mThingsToLoad.empty()) {
                        r = mThingsToLoad.pop();
                    }
                }
                if (r != null) {
                    r.run();
                }
            }
        };

    private final ConversationList.CachingNameStore mCachingNameStore;

    public ConversationListAdapter(Context context, Cursor cursor,
                                   ConversationList.CachingNameStore nameStore) {
        super(context, cursor, true /* auto-requery */);
        mFactory = LayoutInflater.from(context);
        mCachingNameStore = nameStore;
        
        mThreadDisplayFrom = new ConcurrentHashMap<String, String>();
        // 1 thread.  SQLite can't do better anyway.
        mAsyncLoader = new ScheduledThreadPoolExecutor(1);
    }

    /**
     * Returns the from text using the CachingNameStore.
     */
    private String getFromTextFromCache(String spaceSeparatedRcptIds, String address) {
        // Potentially blocking call to Contacts provider, lookup up
        // names:  (should usually be cached, though)
        String value = mCachingNameStore.getContactNames(address);

        if (TextUtils.isEmpty(value)) {
            value = mContext.getString(R.string.anonymous_recipient);
        }

        mThreadDisplayFrom.put(spaceSeparatedRcptIds, value);
        return value;
    }

    /**
     * Returns cached 'from' text of message thread (display form of list of recipients)
     */
    private String getFromTextFromMessageThread(String spaceSeparatedRcptIds) {
        // Thread IDs could in-theory be reassigned to different
        // recipients (if latest threadid was deleted and new
        // auto-increment was assigned), so our cache key is the
        // space-separated list of recipients IDs instead:
        return mThreadDisplayFrom.get(spaceSeparatedRcptIds);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (!(view instanceof ConversationHeaderView)) {
            Log.e(TAG, "Unexpected bound view: " + view);
            return;
        }
        
        ConversationHeaderView headerView = (ConversationHeaderView) view;
        Conversation conv = Conversation.from(context, cursor);

        boolean cacheEntryInvalid = true;
        int presenceIconResId = 0;
        String spaceSeparatedRcptIds = conv.getRecipientIds();
        String from = getFromTextFromMessageThread(spaceSeparatedRcptIds);
        
        // display the presence from the cache. The cache entry could be invalidated
        // in the activity's onResume(), but display the info anyways if it's in the cache.
        // If it's invalid, we'll force a refresh in the async thread.
        ContactInfoCache.CacheEntry entry = conv.getContactInfo(false);
        if (entry != null) {
            presenceIconResId = entry.presenceResId;
            cacheEntryInvalid = entry.isStale();
        }

        if (LOCAL_LOGV) Log.v(TAG, "pre-create ConversationHeader");
                
        ConversationHeader ch = new ConversationHeader(context, conv, from);
        headerView.bind(context, ch);
        headerView.setPresenceIcon(presenceIconResId);

        // if the cache entry is invalid, or if we can't find the "from" field,
        // kick off an async op to refresh the name and presence
        if (cacheEntryInvalid || (from == null && spaceSeparatedRcptIds != null)) {
            startAsyncDisplayFromLoad(context, ch, headerView, spaceSeparatedRcptIds);
        }
        if (LOCAL_LOGV) Log.v(TAG, "post-bind ConversationHeader");
    }

    private void startAsyncDisplayFromLoad(final Context context,
                                           final ConversationHeader ch,
                                           final ConversationHeaderView headerView,
                                           final String spaceSeparatedRcptIds) {
        synchronized (mThingsToLoad) {
            mThingsToLoad.push(new Runnable() {
                    public void run() {
                        String addresses = MessageUtils.getRecipientsByIds(
                                context, spaceSeparatedRcptIds, true /* allow query */);

                        // set from text
                        String fromText = getFromTextFromMessageThread(spaceSeparatedRcptIds);
                        if (TextUtils.isEmpty(fromText)) {
                            fromText = getFromTextFromCache(spaceSeparatedRcptIds, addresses);
                        }

                        int presenceIconResId = 0;

                        if (addresses != null && addresses.indexOf(';') < 0) {
                            // only set presence for single recipient
                            ContactInfoCache.CacheEntry entry = null;
                            ContactInfoCache cache = ContactInfoCache.getInstance();
                            String address = addresses;

                            entry = cache.getContactInfo(address, true);

                            if (entry != null) {
                                presenceIconResId = entry.presenceResId;
                            }

                            if (LOCAL_LOGV) {
                                Log.d(TAG, "ConvListAdapter.startAsyncDisplayFromLoad: " + fromText
                                    + ", presence=" + presenceIconResId + ", cacheEntry=" + entry);
                            }
                        }

                        // need to update the from text and presence icon using a callback, so
                        // they are done in the UI thread
                        ch.setFromAndPresence(fromText, presenceIconResId);
                    }
                });
        }
        mAsyncLoader.execute(mPopStackRunnable);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        if (LOCAL_LOGV) Log.v(TAG, "inflating new view");
        return mFactory.inflate(R.layout.conversation_header, parent, false);
    }

    @Override
    public void changeCursor(Cursor cursor) {
        // Now that we are requerying, bindView will restart anything
        // that might have been pending in the async loader, so clear
        // out its job stack and let it start fresh.
        synchronized (mThingsToLoad) {
            mThingsToLoad.clear();
        }
 
        super.changeCursor(cursor);
    }
    
    public void invalidateAddressCache() {
        mThreadDisplayFrom.clear();
    }
}
