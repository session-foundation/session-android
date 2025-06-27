package org.thoughtcrime.securesms.database.model;

import androidx.annotation.IntDef;

import org.thoughtcrime.securesms.database.RecipientDatabase;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.SOURCE)
@IntDef({RecipientDatabase.NOTIFY_TYPE_MENTIONS, RecipientDatabase.NOTIFY_TYPE_ALL, RecipientDatabase.NOTIFY_TYPE_NONE})
public @interface NotifyType {
}
