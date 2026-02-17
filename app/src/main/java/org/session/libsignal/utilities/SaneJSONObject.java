package org.session.libsignal.utilities;

import org.json.JSONException;
import org.json.JSONObject;

public class SaneJSONObject {
    private final JSONObject delegate;

    public SaneJSONObject(JSONObject delegate) {
        this.delegate = delegate;
    }

    public String getString(String name) throws JSONException {
        if (delegate.isNull(name)) return null;
        else return delegate.getString(name);
    }

    public long getLong(String name) throws JSONException {
        return delegate.getLong(name);
    }

    public boolean isNull(String name) {
        return delegate.isNull(name);
    }

    public int getInt(String name) throws JSONException {
        return delegate.getInt(name);
    }
}
