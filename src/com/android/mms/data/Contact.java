package com.android.mms.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Contacts.People;
import android.provider.Telephony.Mms;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.mms.util.ContactInfoCache;
import com.android.mms.util.TaskStack;

public class Contact {
    private static final String TAG = "Contact";
    private static final boolean V = false;

    private static final TaskStack sTaskStack = new TaskStack();

    private static final ContentObserver sContactsObserver
        = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfUpdate) {
            invalidateCache();
        }
    };

    private final HashSet<UpdateListener> mListeners = new HashSet<UpdateListener>();

    private String mNumber;
    private String mName;
    private String mNameAndNumber;   // for display, e.g. Fred Flintstone <670-782-1123>
    private String mLabel;
    private long mPersonId;
    private int mPresenceResId;      // TODO: make this a state instead of a res ID

    @Override
    public synchronized String toString() {
        return String.format("{ number=%s, name=%s, nameAndNumber=%s, label=%s, person_id=%d }",
                mNumber, mName, mNameAndNumber, mLabel, mPersonId);
    }

    public interface UpdateListener {
        public void onUpdate(Contact updated);
    }

    private Contact(String number) {
        mNumber = number;
        mName = "";
        mNameAndNumber = formatNameAndNumber(mName, mNumber);
        mLabel = "";
        mPersonId = 0;
        mPresenceResId = 0;
    }

    private static void logWithTrace(String msg, Object... format) {
        Thread current = Thread.currentThread();
        StackTraceElement[] stack = current.getStackTrace();

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(current.getId());
        sb.append("] ");
        sb.append(String.format(msg, format));

        sb.append(" <- ");
        int stop = stack.length > 7 ? 7 : stack.length;
        for (int i = 3; i < stop; i++) {
            String methodName = stack[i].getMethodName();
            sb.append(methodName);
            if ((i+1) != stop) {
                sb.append(" <- ");
            }
        }

        Log.d(TAG, sb.toString());
    }

    public static Contact get(String number, boolean canBlock) {
        if (V) logWithTrace("get(%s, %s)", number, canBlock);

        if (TextUtils.isEmpty(number)) {
            throw new IllegalArgumentException("Contact.get called with null or empty number");
        }

        Contact contact = Cache.get(number);
        if (contact == null) {
            contact = new Contact(number);
            Cache.put(contact);
            updateContact(number, canBlock);
        }
        return contact;
    }

    public static void invalidateCache() {
        if (V) Log.d(TAG, "invalidateCache");
        // force invalidate the contact info cache, so we will query for fresh info again.
        // This is so we can get fresh presence info again on the screen, since the presence
        // info changes pretty quickly, and we can't get change notifications when presence is
        // updated in the ContactsProvider.
        ContactInfoCache.getInstance().invalidateCache();

        // Queue updates for the whole cache.
        sTaskStack.clear();
        String[] numbersToUpdate = Cache.getNumbers();
        for (String number : numbersToUpdate) {
            updateContact(number, false);
        }
    }

    private static String emptyIfNull(String s) {
        return (s != null ? s : "");
    }

    private static boolean contactChanged(Contact orig, ContactInfoCache.CacheEntry newEntry) {
        // The phone number should never change, so don't bother checking.
        // TODO: Maybe update it if it has gotten longer, i.e. 650-234-5678 -> +16502345678?

        String oldName = emptyIfNull(orig.mName);
        String newName = emptyIfNull(newEntry.name);
        if (!oldName.equals(newName)) {
            if (V) Log.d(TAG, String.format("name changed: %s -> %s", oldName, newName));
            return true;
        }

        String oldLabel = emptyIfNull(orig.mLabel);
        String newLabel = emptyIfNull(newEntry.phoneLabel);
        if (!oldLabel.equals(newLabel)) {
            if (V) Log.d(TAG, String.format("label changed: %s -> %s", oldLabel, newLabel));
            return true;
        }

        if (orig.mPersonId != newEntry.person_id) {
            if (V) Log.d(TAG, "person id changed");
            return true;
        }

        if (orig.mPresenceResId != newEntry.presenceResId) {
            if (V) Log.d(TAG, "presence changed");
            return true;
        }

        return false;
    }

    private static void updateContact(final String number, boolean canBlock) {
        Runnable r = new Runnable() {
            public void run() {
                // TODO: move the querying into this file from ContactInfoCache
                ContactInfoCache cache = ContactInfoCache.getInstance();
                ContactInfoCache.CacheEntry entry = cache.getContactInfo(number);
                Contact c = Cache.get(number);
                if (c == null) {
                    Log.w(TAG, "updateContact: contact not in cache");
                    return;
                }
                synchronized (Cache.getInstance()) {
                    if (contactChanged(c, entry)) {
                        //c.mNumber = entry.phoneNumber;
                        c.mName = entry.name;
                        c.mNameAndNumber = formatNameAndNumber(c.mName, c.mNumber);
                        c.mLabel = entry.phoneLabel;
                        c.mPersonId = entry.person_id;
                        c.mPresenceResId = entry.presenceResId;

                        for (UpdateListener l : c.mListeners) {
                            if (V) Log.d(TAG, "updating " + l);
                            l.onUpdate(c);
                        }
                    }
                }
            }
        };

        if (canBlock) {
            r.run();
        } else {
            sTaskStack.push(r);
        }
    }

    public static String formatNameAndNumber(String name, String number) {
        // Format like this: Mike Cleron <(650) 555-1234>
        //                   Erick Tseng <(650) 555-1212>
        //                   Tutankhamun <tutank1341@gmail.com>
        //                   (408) 555-1289
        String formattedNumber = number;
        if (!Mms.isEmailAddress(number)) {
            formattedNumber = PhoneNumberUtils.formatNumber(number);
        }

        if (!TextUtils.isEmpty(name) && !name.equals(number)) {
            return name + " <" + formattedNumber + ">";
        } else {
            return formattedNumber;
        }
    }

    public synchronized String getNumber() {
        return mNumber;
    }

    public synchronized String getName() {
        if (TextUtils.isEmpty(mName)) {
            return mNumber;
        } else {
            return mName;
        }
    }

    public synchronized String getNameAndNumber() {
        return mNameAndNumber;
    }

    public synchronized String getLabel() {
        return mLabel;
    }

    public synchronized Uri getUri() {
        return ContentUris.withAppendedId(People.CONTENT_URI, mPersonId);
    }

    public synchronized int getPresenceResId() {
        return mPresenceResId;
    }

    public synchronized boolean existsInDatabase() {
        return (mPersonId > 0);
    }

    public synchronized void addListener(UpdateListener l) {
        mListeners.add(l);
    }

    public synchronized void removeListener(UpdateListener l) {
        mListeners.remove(l);
    }

    public synchronized boolean isEmail() {
        return Mms.isEmailAddress(mNumber);
    }

    public static void init(final Context context) {
        RecipientIdCache.init(context);
        context.getContentResolver().registerContentObserver(
                People.CONTENT_URI, true, sContactsObserver);
    }

    public static void dump() {
        Cache.dump();
    }

    private static class Cache {
        private static Cache sInstance = new Cache();
        static Cache getInstance() { return sInstance; }
        private final List<Contact> mCache;
        private Cache() {
            mCache = new ArrayList<Contact>();
        }

        static void dump() {
            synchronized (sInstance) {
                Log.d(TAG, "**** Contact cache dump ****");
                for (Contact c : sInstance.mCache) {
                    Log.d(TAG, c.toString());
                }
            }
        }

        private static Contact getEmail(String number) {
            synchronized (sInstance) {
                for (Contact c : sInstance.mCache) {
                    if (number.equalsIgnoreCase(c.mNumber)) {
                        return c;
                    }
                }
                return null;
            }
        }

        static Contact get(String number) {
            if (Mms.isEmailAddress(number))
                return getEmail(number);

            synchronized (sInstance) {
                for (Contact c : sInstance.mCache) {
                    if (PhoneNumberUtils.compare(number, c.mNumber)) {
                        return c;
                    }
                }
                return null;
            }
        }

        static void put(Contact c) {
            synchronized (sInstance) {
                // We update cache entries in place so people with long-
                // held references get updated.
                if (get(c.mNumber) != null) {
                    throw new IllegalStateException("cache already contains " + c);
                }
                sInstance.mCache.add(c);
            }
        }

        static String[] getNumbers() {
            synchronized (sInstance) {
                String[] numbers = new String[sInstance.mCache.size()];
                int i = 0;
                for (Contact c : sInstance.mCache) {
                    numbers[i++] = c.getNumber();
                }
                return numbers;
            }
        }
    }
}