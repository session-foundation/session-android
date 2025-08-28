/**
 * Copyright (C) 2015 Open Whisper Systems
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
package org.thoughtcrime.securesms.database;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView.Adapter that manages a Cursor, comparable to the CursorAdapter usable in ListView/GridView.
 */
public abstract class CursorRecyclerViewAdapter<VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH> {
  private final Context context;

  @Nullable
  private           Cursor  cursor = null;

  protected CursorRecyclerViewAdapter(Context context) {
    this.context = context;
  }

  protected @NonNull Context getContext() {
    return context;
  }

  public @Nullable Cursor getCursor() {
    return cursor;
  }

  public boolean isActiveCursor() {
    return true;
  }

  public void changeCursor(Cursor cursor) {
    Cursor old = swapCursor(cursor);
    if (old != null) {
      old.close();
    }
  }

  public Cursor swapCursor(Cursor newCursor) {
    if (newCursor == cursor) {
      return null;
    }

    final Cursor oldCursor = cursor;
    cursor = newCursor;
    notifyDataSetChanged();
    return oldCursor;
  }

  @Override
  public final int getItemCount() {
    return cursor == null ? 0 : cursor.getCount();
  }


  protected void onItemViewRecycled(VH holder) {}

  @Override
  public final void onBindViewHolder(@NonNull VH viewHolder, int position) {
    if (cursor == null || !cursor.moveToPosition(position)) {
      throw new IllegalArgumentException("Invalid position: " + position);
    }

    onBindItemViewHolder(viewHolder, cursor, position);
  }

  protected abstract void onBindItemViewHolder(VH viewHolder, @NonNull Cursor cursor, int position);


  @Override
  public final int getItemViewType(int position) {
    if (cursor == null) {
      throw new IllegalStateException("Cursor not set");
    }
    cursor.moveToPosition(position);
    return getItemViewType(cursor);
  }

  public abstract int getItemViewType(@NonNull Cursor cursor);
  public abstract long getItemId(@NonNull Cursor cursor);

  @Override
  public final long getItemId(int position) {
    if (cursor == null) {
      throw new IllegalStateException("Cursor not set");
    }

    cursor.moveToPosition(position);
    return getItemId(cursor);
  }
}
