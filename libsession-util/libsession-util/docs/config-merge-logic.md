When we have competing config message, we need completely consistent logic for merging them, that is
both forwards and backwards compatible (that is: old clients with new data, and new clients with old
data, need to produce an agreeable result).

The way this is implemented here is as follows:

- each config message has a "seqno"; effectively a version number that gets incremented over time.
- when making any change to configuration data, a client constructs a new message by copying all the
  current message data (whether or not it knows the interpretation of that data), increments the
  `seqno`, and includes the intended modifications.
- the client creates a batch request that pushes the new configuration and simultaneously deletes
  (if permitted) any known, older configuration message(s) that are obsoleted by the new
  configuration message.
- every client need to agree exactly on which message "wins" in the case of 2 (or more) competing
  messages, and so for tie-breaking we use string comparison on the two (encoded) config messages.
- whichever one wins, any client that observes a conflict (i.e. same seqno) needs to merge the two
  messages, increment the seqno, and push the new one to the swarm.

# Structure of a config message

A decrypted config message consists of outer data (which contains generic information about the
config message), and the inner message data, which contains the actual application-specific
configuration data, embedded within the outer data.

This config message is encrypted when stored, using an encryption key that is known to anyone who
needs to decrypt it.  (How to obtain that key is use-case dependent and outside the scope of this
document).  The specifics of encryption are covered in the [Message Encryption](#message-encryption)
section, below.

## Application-side config data

To start with the inner config data (which will be under the `"&"` key of the outermost data
message; see description in the [outer metadata](#config-message-outer-metadata), this is a dict
which has byte string keys (of max length 128 bytes) and values which are any of:

- integers, which are signed 64-bit quantities.
- strings, which are of max length 4096 bytes.
- sets of one or more values, which may contain unique integer or string values (but not other sets
  or dicts).  These must be sorted by integer or byte value for ints or strings, respectively;
  integers sort before strings if a set contains both.  Sets must contain at least one value: empty
  sets are omitted from the encoded data.  (Clients that need empty sets should either treat a
  missing set value as empty, or else use some other way of encoding "empty" vs "not-present" in the
  data.)
- dicts of byte string keys to a value of any type (integer, string, set, dict).  Dicts always have
  at least one key: an empty dict is omitted entirely (except for the outermost config data dict
  itself).  As with sets, if a client needs to distinguish between an empty set and an omitted set
  then it needs to encode this data in the data structure itself.

These are encoded as ints, strings, lists, or dicts using bt-encoding.  Note that the data
requirements here are somewhat more restrictive than what bt-encoding allows: in particular, it
allows arbitrary values inside lists, doesn't impose list ordering or uniqueness, and allows empty
lists/dicts.  All of those are excluded from config data messages so as to make merging of messages
feasible and deterministic.

## Config message outer metadata

- The top-level structure of a config message is always a dict, with keys as follows (note that the
  restrictions described above for the application data do *not* apply to the outer config message
  structure):
  - any key before `"#"` indicates a major version break of the config message structure, and
    instructs clients that they are not able to parse the config message at all and therefore should
    ignore it.  (It is hoped that this is never needed, but provided in case future use requires
    forcing non-upgraded clients to ignore a message).
  - `"#"` = integer seqno of the message.
  - `"&"` = a dict of the actual inner application config data.
  - `"<"` = lagged config diffs of past updates (see [Config diffs](#config-diffs) below)
  - `"="` = config diff of changes applied in this update (see [Config diffs](#config-diffs) below)
  - `"~"` = (Sometimes) Ed25519 signature of the encoded config message, not including this
    signature keypair.  If present this key *must* be the last field of the data and the value
    *must* be exactly 64 bytes.  The signature itself will be over the encoded value up to but not
    including the final `1:~64:[64bytes]e`: that is, it omits the signing pair and the final `e`
    ending the overall data structure.

    This field is only used for certain types of config messages (such as closed group messages)
    where authentication of the message creator is required.  This field, when present, *must* be
    the last key (i.e. no top-level keys that sort after `~` are permitted).

# Config diffs

To enable clients to merge config messages (even of potential content from future clients it does
not understand) any config changes are included in the "diff" section of the config (keys `"<"` and
`"="`).  These data are structured as follows:

- key `"<"` contains the lagged diffs, that is, all diffs embedded in this update within the last
  few `seqno` increments (e.g. for an update with seqno 20, this contains the diffs for all absorbed
  updates with a seqno from 16 through 19, when using a "within 5" rule; see the [ignored
  updates](#ignored-updates) section below for the "within N" rule).

  The value here is a list of which each element is a 3-tuple (i.e.  three-element list when
  encoded) of `[seqno, "hash", {...diff...}]`, where `seqno` was the integer seqno of the update;
  `"hash"` is the 32-byte, unkeyed BLAKE2b hash of its overall config message; and `"diff"` is as
  described in the [Config diff updates](#config-diff-updates) section below.

  This list must be ordered by seqno (primary) and hash (secondary) and gives an ability to "replay"
  recent changes during conflict resolution to merge competing changes, as described in the
  [Conflict resolution](#conflict-resolution) section, below.

- key `"="` contains the new diff of updates applied in *this* message; it is a dict that mirrors
  the main application config data, but with placeholder values to indicate changes (rather than
  duplicating values).

## Config diff updates

A config diff itself (i.e. the third element of a `"<"` tuple, or the `"="` value) is a dict that
largely mirrors the inner config data structure (i.e.  under the `"&"` outer config key) but that
only records fields that were specifically changed as part of this update, but *not* include changes
that were the result of merging other updates.  That is, if a message with seqno 456 adds a new key
and also resolves a merge conflict which adds a second key then only the first key is included in
the diff (the second key will already be included in one of the lagged diff entries).

A diff update itself is a dict such that:

- the key indicates the equivalent key inside the config data (`"&"`).
- a value of `""` (empty string) indicate that a string or integer value was assigned (whether added
  or changed).
- a value of `"-"` indicates that a string or integer value was removed from the dict.
- Note that dicts and sets are never explicitly listed as additions or removals at all: rather they
  are implicitly removed when empty, and implicit autovivified (if needed) when added to.
- a dict value indicates that there were changes within an existing sub-dict of the given name.
  This diff specification recurses for changes within that dict.  Sub-dicts are created or removed
  as needed (created when the first subkey is added; removed when the last subkey is removed).
- a value that is a list must be a pair of sublists which indicates that the referenced key is a set
  and that changes were applied to it.  The first element of the sublist is the subset of the list's
  new values; the second is a subset of removed values.  Both are always required, but one can be
  empty if there were only additions or only removals.  Similarly to dict handling, removing *all*
  values implicitly removes the set itself, and adding to a non-existent set autovivifies the set.

# Client update behaviour

When there are multiple conflicting config messages clients resolve according to several rules.

## Ignored updates

Clients ignore updates according to two criteria:

- "within N": a client ignores any update with a seqno value that is not within the last N seqnos.
  For instance if current seqno is 123 and N=5 then the client only considers messages with a seqno
  in {119, 120, 121, 122, 123}, ignoring any config message with a seqno of 118 or earlier.

  This choice is configurable: higher values allow for a larger conflict window before data is
  dropped, while lower values reduce the overhead of the diffs that are included in messages to
  handle conflicts.

- "ignore included": if a client sees two config messages with different seqno values and the
  smaller one is contained within the diff of the larger one then the smaller one is stale and will
  be ignored.

- invalid messages: messages that are unparseable or fail some of the other requirements shall be
  ignored; for example:
  - duplicates or improperly sorted data inside a application data list
  - missing required fields
  - keys before `"#"` in the config message
  - improperly sorted lagged config diffs
  - missing or invalid signature (in contexts where signatures are required)
  - etc.

## Non-conflicting updates

If a client is making a configuration change and there is only a single current valid config message
(i.e. no conflicts to resolve) then the update procedure is straightforward:

1. A new seqno is assigned that is 1 higher than the current largest seqno.
2. The lagged config diffs from the existing config message are copied into the new message,
   omitting config messages with seqno values less than or equal to (newseqno-N).  The client should
   ensure the values are sorted by seqno, hash.  (They should be sorted already, but clients should
   not propagate wrongly sorted lists if they encounter one).
3. The current config message's diff is appended to the lagged config diff (using the message's
   seqno value and calculating a hash of the message to complete the 3-tuple).
4. A current diff is constructed for any values being assigned, and stored under the `"="` key of
   the config message.

## Conflict resolution

Conflict resolution logic kicks in if, after removing ignored messages from consideration, there are
still multiple valid config messages: these messages must be merged and the merge update published.

Merging is performed as follows:

1. A new seqno is assigned that is 1 higher than the current largest seqno.
2. All messages are sorted by their seqno (first) and hash (second).
3. The "current data" is initialized from the full data of the highest value (i.e. highest hash of
   the highest seqno).
4. A replay set is constructed; this is ordered and must contain some reference to the message that
   contains the diff (so that added/changed values can have their value retrieved from the
   appropriate message).
   - the "current" diff of every message is added to the replay sets (the seqno and hash are
     extracted and computed from the message itself).
   - past diffs in the messages (i.e. from the `"<"` list) are added to the replay set, *if* they
     are not yet in the replay set, and have a seqno >= newseqno-5.  This should be done in order
     from highest-to-lowest ranked message (thus if there is any disagreement of the contents of a
     replay diff it is higher-ranked messages that are used).
5. The replay set is ordered such that lower seqnos come before higher seqnos, and lower hashes come
   before higher hashes.
6. The replay set is processed in order: hash keys are assigned or removed according to the `""` or
   `"-"` value, and sets have elements assigned or removed according to the add/remove set diff
   lists.  (Removals of list or dict keys that are not present in the current data are ignored).

   Sets or dicts that have become empty after processing a replay entry shall be removed (e.g.
   recursively, using a depth-first search, or some equivalent approach).
7. The replay set is copied into the lagged diffs (key `"<"`), but excluding any entries with seqno
   of (newseqno-5).  Seqnos of that value are included for replay since all messages will have them,
   but are not included or needed for replay of future seqno messages.
8. The message diff itself:
   - If the config message is making changes aside from the merge then the diff of changes is
     constructed and written into the `"="` key.
   - Otherwise (i.e. only merge changes) the current diff key `"="` is set to an empty dict.

# Examples

The following depict several examples showing how update rules work.  Values are shown in
not-quite-JSON (i.e. we add comments) for human readability, but in reality will be bt-encoded.

Hash values listed in the form `"(hashXXX)"` would be the actual 32-byte BLAKE2b hash strings of the
relevant messages.  When multiple competing messages with the same seqno are involved the hash will
be noted as "(hashXXXa)", "(hashXXXb)", etc. where `a`, `b`, etc. reflect the byte-string sorted
order of the hashes with the same XXX seqno value (lower letters = earlier-sorting hash).

All examples use a "within 5" rule for determining how the seqno cutoff for conflict resolution, and
are not using signatures.

## Ordinary update

Suppose an update begins from the following data (with seqno 122), and updates have all been linear
and orderly (i.e. there have been no recent config conflicts):

```json
    {
        "dictB": {
            "changed": -1,
            "foo": 123,
            "removed": "x",
            "removed2": "y"
        },
        "dictC": {
            "x": {
                "y": 1
            }
        },
        "good": [99, 456, "bar"],
        "great": [-42, "omg"],
        "int0": -9999,
        "int1": 100,
        "string1": "hello",
        "string2": "goodbye"
    }
```

Now a client wants to update it by changing `["int1"]` to 1, and adding a new key `["int2"]` set to
2, and deleting the `"int0"` key.  This new message then gets seqno 123; the full update then looks
like this:

```json
    {
        "#": 123,
        "&": {  // The new full data
            "dictB": {
                "changed": -1,
                "foo": 123,
                "removed": "x",
                "removed2": "y"
            },
            "dictC": {
                "x": {
                    "y": 1
                }
            },
            "good": [99, 456, "bar"],
            "great": [-42, "omg"],
            "int1": 1,
            "int2": 2,
            "string1": "hello",
            "string2": "goodbye"
        },
        "<": [
            [119, "(hash119)", {...changes-in-seqno-119...}],
            [120, "(hash120)", {...changes-in-seqno-120...}],
            [121, "(hash121)", {...changes-in-seqno-121...}],
            [122, "(hash122)", {...changes-in-seqno-122...}],
        ],
        "=": {
            "int0": "-", // removed
            "int1": "",  // changed
            "int2": ""   // added
        }
    }
```


## Large, but still ordinary, update

Suppose an update begins from the following data (with seqno 123), and updates have all been linear
and orderly (i.e. there have been no recent config conflicts):

```json
    {
        "dictB": {
            "changed": -1,
            "foo": 123,
            "removed": "x",
            "removed2": "y"
        },
        "dictC": {
            "x": {
                "y": 1
            }
        },
        "good": [99, 456, "bar"],
        "great": [-42, "omg"],
        "int1": 1,
        "int2": 2,
        "string1": "hello",
        "string2": "goodbye"
    }
```

The client modifies it quite substantially to the following:

```json
    {
        "dictA": {
            "hello": 123,
            "goodbye": [123, 456]
        },
        "dictB": {
            "added": 9999,
            "changed": 1,
            "foo": 123,
            "nested": {"a", 1}
        },
        "good": [99, 123, "Foo", "bar"],
        "int1": 42,
        "int2": 2,
        "string2": "hello",
        "string3": "omg"
    }
```

The overall record of this change looks as follows.

```json
    {
        "#": 124,
        "&": {  // The current full data
            "dictA": {
                "goodbye": [123, 456],
                "hello": 123
            },
            "dictB": {
                "added": 9999,
                "changed": 1,
                "foo": 123,
                "nested": {
                    "a": 1
                }
            },
            "good": [99, 123, "Foo", "bar"],
            "int1": 42,
            "int2": 2,
            "string2": "hello",
            "string3": "omg"
        },
        "<": [
            [120, "(hash120)", {...changes-in-seqno-120...}],
            [121, "(hash121)", {...changes-in-seqno-121...}],
            [122, "(hash122)", {...changes-in-seqno-122...}],
            [123, "(hash123)", {...changes-in-seqno-123...}]
        ],
        "=": {
            "dictA": {
                "goodbye": "",
                "hello": ""
            },
            "dictB": {
                "added": "",
                "changed": "",
                "nested": {
                    "a": ""
                },
                "removed": "-",
                "removed2": "-"
            },
            "dictC": {
                "x": {
                    "y": "-"  // last key of dictC.x removes it
                }
            }, // And since "x" was the only key in distC, distC now gets removed too
            "good": [ // a list here indicates changes to a set
                [123, "Foo"], // Additions
                [456],        // Removals
            ],
            "great": [
                [],
                [-42, "omg"]
            ], // Removed all elements so also removes "great"
            "int1": "",     // changed
            "string1": "-", // removed
            "string2": "",  // changed
            "string3": ""   // added
        }
    }
```

## Simple conflict resolution

Suppose two clients now push update with `seqno=125` where one client sets `["int1"]` to `5` and
another removes element `["dictB"]["foo"]`.

Each publish a config message update but they have the same seqno; the relevant part of the new
update will be:

```json
    ...
        "]": { "int1", "" }
```

for the first client, with message hash "(hash125b)"; and

```json
    ...
        "]": { "dictB": { "foo": "-" } }
```

for the second client, with message hash "(hash125a)".  (Note that the second client's message sorts
before the first client's message by virtual of having a "smaller" hash value).

A client (which could be either of the publishers, or some third client) can resolve this by
publishing an update with `seqno=126` that resolves the conflict; regardless of which client
publishes, the client will merge the two into the exact same update which consists of:

```json
    {
        "#": 126,
        "&": {  // The current full data
            "dictA": {
                "hello": 123,
                "goodbye": [123, 456]
            },
            "dictB": {
                "added": 9999,
                "changed": 1,
                "nested": {
                    "a": 1
                }
            },
            "good": [99, 123, "Foo", "bar"],
            "int1": 5,
            "int2": 2,
            "string2": "hello",
            "string3": "omg"
        },
        "<": [
            [122, "(hash122)", {...changes-in-seqno-122...}],
            [123, "(hash123)", {...changes-in-seqno-123...}],
            [124, "(hash124)", {...changes-in-seqno-124...}],
            // NB: we have *two* 125 entries here:
            [125, "(hash125a)", { "dictB": { "foo": "-" }}],
            [125, "(hash125b)", { "int1", "" }],
        ],
        "=": {} // No changes aside from the merge
    }
```

Since all clients will produce an identical message, even if multiple clients push this to the swarm
at once, it will simply be de-duplicated and stored only once.


## Stale messages

If a message arrives with a seqno that is not within the most recent five seqno values of the
largest-seqno message then it is simply dropped.  For instance supposed we have seqno 126 (from the
previous example) and a message with seqno 121 arrives from a client (perhaps it was significantly
out of date and has a delayed update still to go out).  This message neither competes with the
current seqno (126) nor any of the historic ones (122 through 125), and so it is discarded.


## Complex conflict resolution

Suppose that while the resolution from the previous example is happening there is another client
that is somewhat out of date and submitting a configuration update with `seqno=124` (we'll label
this as "124a") that removes the `["dictA"]["hello"]` value from `seqno=123`, and also sorts
*before* the existing `seqno=124` update from a previous example, which we'll now label `124b`.

Meanwhile there is yet another *far* out of date client that publishes an alternative update
`seqno=120`.

A client observes all of 125a, 125b, 124a, and 120b as current (i.e. not contained within another
message) and resolves them.  But this results in *two* different 126 merges (the one from the
previous example, now labelled "126a", and the three-way merge, labelled "126b"), and so finally
these have to be merged again into a single 127.

Just to keep it from getting too simple (and to explore a case that could happen) we'll also suppose
that one of the client publishing the 127 merge *also* wants to add 789 into the
`["dictA"]["goodbye"]` set (this additional could well conflict with other clients merging 126a/b
together, and thus require an additional merge to 128, but we're going to assume this client gets
lucky and publishes the update before anyone else notices).

Message 120b gets dropped entirely: by the time it is observed there is already a message with
`seqno=125` and so it misses the `seqno>=121` cutoff.

This means, we now have a tree of changes that look like this:

```
                             |          |
                            122         |
                             |          |
                            123         |
                           /   \        |
                        124b    \       |
                       /    \    \      |
                     125a  125b  |      |
                      |  \/  |  124a    |
                      |  /\  |  /     120b
                      | /  \ | /    (deleted without merge)
                     126a   126b
                        \   /
                         127
```

124b (previously just 124), 125a, 125b, and 126a (previously just 126) are as described in previous
examples.

Update 124a will be a relative simple update that sets ["dictB"]["foo"] to 66 and
["dictB"]["answer"] to 42, and thus has diff:

```
        "=": { "dictB": { "answer": "", "foo": "" } }
```

(Note that this change to "foo" conflicts with the removal of "foo" in 125b, and because 125b > 124a
the 125b removal will take precedence).

Update 126b does a three-way merge in which it starts from the top-sorted data, "125b", then replays
(in order) everything in its previous diffs, in seqno-then-hash sorted order: 121, 122, 123, 124a,
124b, 125a, 125b.  The final update value will then end up as:

```json
    {
        "#": 126,
        "&": {  // The current full data
            "dictA": {
                "goodbye": [123, 456]
            },
            "dictB": {
                "added": 9999,
                "answer": 42,
                "changed": 1,
                "nested": {
                    "a": 1
                }
            },
            "good": [123, "Foo", "bar"],
            "int1": 5,
            "int2": 2,
            "string2": "hello",
            "string3": "omg"
        },
        "*": {
            "<": [
                [122, "(hash122)", {...changes-in-seqno-122...}],
                [123, "(hash123)", { "int0": "-", "int1": "", "int2": "" }],
                [124, "(hash124a)", { "dictB": {"answer": "", "foo: ""} },
                [124, "(hash124b)", {...large-changes-from-124-example...}],
                [125, "(hash125a)", { "dictB": { "foo": "-" }}],
                [125, "(hash125b)", { "int1", "" }],
            ],
            "=": {} // No changes aside from the merge
        }
    }
```

Note that the 121 update is included for calculation (because it is part of 125a and 125b) but not
included in the final 126b message itself (because it is beyond the cutoff for seqno 126).

127 is a two-way merge between 126a and 126b but, as mentioned earlier, also adds 789 to the
`["dictA"]["goodbye"]` set.  It does this by following the merge and replay logic: it starts from
126b (the highest by seqno/hash ranking), then replays changes from 122, 123, 124a, 124b, 125a,
125b, 126a, 126b on top of it, and then finally re-applies local change from 126b and pushes the
result.

Aside from the added value (789) this 127 merge doesn't actually change anything through merging:
126a already includes everything that 126b does (and doesn't have any additional changes of its
own).  The 127 update still happens, however, to "commit" the merge (even though it doesn't affect
anything), allowing 126a and b to be forgotten.

This final 127 update thus becomes:

```json
    {
        "#": 127,
        "&": {  // The current full data
            "dictA": {
                "goodbye": [123, 456, 789],
                "hello": 123
            },
            "dictB": {
                "added": 9999,
                "answer": 42,
                "changed": 1,
                "nested": {
                    "a": 1
                }
            },
            "good": [123, "Foo", "bar"],
            "int1": 5,
            "int2": 2,
            "string2": "hello",
            "string3": "omg"
        },
        "*": {
            "<": [
                [123, "(hash123)", { "int0": "-", "int1": "", "int2": "" },
                [124, "(hash124a)", { "dictB": { "answer": "", "foo": "" }}],
                [124, "(hash124b)", {...large-changes-from-124-example...}],
                [125, "(hash125a)", { "int1", "" }],
                [125, "(hash125b)", { "dictB": { "foo": "-" }}],
                [126, "(hash126a)", {}],
                [126, "(hash126b)", {}]
            ],
            "=": { "dictA": { "goodbye": [[789], []] } }
        }
    }
```

# Message Encryption

All messages are stored in encrypted form; we select XChaCha20-Poly1305 encryption for its excellent
properties.

One complication in the above-described mechanism is that it is explicitly designed to allow clients
to race to resolve conflicts by assuring that racing clients are all racing to publish identical
data.  This race is intended to be resolved at the server level through server-side de-duplication
of identical messages.  When adding encryption on top, however, this means we also need
deterministic encryption so that the *encrypted* version of the data is also unconflicted.

Thus for encryption we compute the XChaCha20 nonce by not using a pure random nonce but rather using
a 24-byte BLAKE2b keyed hash of the plaintext config message data, keys using 32-byte key
`"libsession-config-nonce-hash-key"`.

Note, however, that message hashes (as used in diff sections) depend on the plaintext serialized
value, not the encrypted value.
