package org.thoughtcrime.securesms.search.model;

import androidx.annotation.NonNull;

import org.session.libsession.utilities.GroupRecord;
import org.session.libsession.utilities.recipients.BasicRecipient;
import org.session.libsession.utilities.recipients.Recipient;
import org.thoughtcrime.securesms.database.CursorList;

import java.util.List;

/**
 * Represents an all-encompassing search result that can contain various result for different
 * subcategories.
 */
public class SearchResult {

  public static final SearchResult EMPTY = new SearchResult("", CursorList.emptyList(), CursorList.emptyList(), CursorList.emptyList());

  private final String                    query;
  private final List<BasicRecipient.Contact>     contacts;
  private final List<GroupRecord>  conversations;
  private final CursorList<MessageResult> messages;

  public SearchResult(@NonNull String                    query,
                      @NonNull List<BasicRecipient.Contact>     contacts,
                      @NonNull List<GroupRecord>  conversations,
                      @NonNull CursorList<MessageResult> messages)
  {
    this.query         = query;
    this.contacts      = contacts;
    this.conversations = conversations;
    this.messages      = messages;
  }

  public List<BasicRecipient.Contact> getContacts() {
    return contacts;
  }

  public List<GroupRecord> getConversations() {
    return conversations;
  }

  public List<MessageResult> getMessages() {
    return messages;
  }

  public String getQuery() {
    return query;
  }

  public int size() {
    return contacts.size() + conversations.size() + messages.size();
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  public void close() {
    messages.close();
  }
}
