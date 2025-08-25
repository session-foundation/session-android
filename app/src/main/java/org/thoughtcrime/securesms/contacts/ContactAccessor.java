/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.contacts;

import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import org.session.libsession.utilities.GroupRecord;
import org.session.libsession.utilities.TextSecurePreferences;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.dependencies.DatabaseComponent;

import java.util.LinkedList;
import java.util.List;

import network.loki.messenger.R;

/**
 * This class was originally a layer of indirection between
 * ContactAccessorNewApi and ContactAccesorOldApi, which corresponded
 * to the API changes between 1.x and 2.x.
 *
 * Now that we no longer support 1.x, this class mostly serves as a place
 * to encapsulate Contact-related logic.  It's still a singleton, mostly
 * just because that's how it's currently called from everywhere.
 *
 * @author Moxie Marlinspike
 */

public class ContactAccessor {

  private static final ContactAccessor instance = new ContactAccessor();

  public static synchronized ContactAccessor getInstance() {
    return instance;
  }

  public List<String> getNumbersForThreadSearchFilter(Context context, String constraint) {
    LinkedList<String> numberList = new LinkedList<>();

    GroupRecord record;
    try (GroupDatabase.Reader reader = DatabaseComponent.get(context).groupDatabase().getGroupsFilteredByTitle(constraint)) {
      while ((record = reader.getNext()) != null) {
        numberList.add(record.getEncodedId());
      }
    }

    return numberList;
  }

  public static class NumberData implements Parcelable {

    public static final Parcelable.Creator<NumberData> CREATOR = new Parcelable.Creator<NumberData>() {
      public NumberData createFromParcel(Parcel in) {
        return new NumberData(in);
      }

      public NumberData[] newArray(int size) {
        return new NumberData[size];
      }
    };

    public final String number;
    public final String type;


    public NumberData(Parcel in) {
      number = in.readString();
      type   = in.readString();
    }

    public int describeContents() {
      return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
      dest.writeString(number);
      dest.writeString(type);
    }
  }

  public static class ContactData implements Parcelable {

    public static final Parcelable.Creator<ContactData> CREATOR = new Parcelable.Creator<ContactData>() {
      public ContactData createFromParcel(Parcel in) {
        return new ContactData(in);
      }

      public ContactData[] newArray(int size) {
        return new ContactData[size];
      }
    };

    public final long id;
    public final String name;
    public final List<NumberData> numbers;

    public ContactData(Parcel in) {
      id      = in.readLong();
      name    = in.readString();
      numbers = new LinkedList<NumberData>();
      in.readTypedList(numbers, NumberData.CREATOR);
    }

    public int describeContents() {
      return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
      dest.writeLong(id);
      dest.writeString(name);
      dest.writeTypedList(numbers);
    }
  }

}
