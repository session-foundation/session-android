package org.session.libsession.utilities;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ContextThemeWrapper;

import org.session.libsignal.utilities.Log;

import network.loki.messenger.R;

public class ThemeUtil {
  private static final String TAG = ThemeUtil.class.getSimpleName();

  public static boolean isDarkTheme(@NonNull Context context) {
    return getAttributeText(context, R.attr.theme_type, "light").equals("dark");
  }

  public static boolean isLightTheme(@NonNull Context context) {
    return getAttributeText(context, R.attr.theme_type, "light").equals("light");
  }

  public static boolean getThemedBoolean(@NonNull Context context, @AttrRes int attr) {
    TypedValue      typedValue = new TypedValue();
    Resources.Theme theme      = context.getTheme();

    if (theme.resolveAttribute(attr, typedValue, true)) {
      return typedValue.data != 0;
    }

    return false;
  }

  @ColorInt
  public static int getThemedColor(@NonNull Context context, @AttrRes int attr) {
    TypedValue typedValue = new TypedValue();
    Resources.Theme theme = context.getTheme();

    if (theme.resolveAttribute(attr, typedValue, true)) {
      return typedValue.data;
    } else {
      Log.e(TAG, "Couldn't find a color attribute with id: " + attr);
      return Color.RED;
    }
  }

  @DrawableRes
  public static int getThemedDrawableResId(@NonNull Context context, @AttrRes int attr) {
    TypedValue typedValue = new TypedValue();
    Resources.Theme theme = context.getTheme();

    if (theme.resolveAttribute(attr, typedValue, true)) {
      return typedValue.resourceId;
    } else {
      Log.e(TAG, "Couldn't find a drawable attribute with id: " + attr);
      return 0;
    }
  }

  public static @Nullable
  Drawable getThemedDrawable(@NonNull Context context, @AttrRes int attr) {
    TypedValue      typedValue = new TypedValue();
    Resources.Theme theme      = context.getTheme();

    if (theme.resolveAttribute(attr, typedValue, true)) {
      return AppCompatResources.getDrawable(context, typedValue.resourceId);
    }

    return null;
  }

  public static LayoutInflater getThemedInflater(@NonNull Context context, @NonNull LayoutInflater inflater, @StyleRes int theme) {
    Context contextThemeWrapper = new ContextThemeWrapper(context, theme);
    return inflater.cloneInContext(contextThemeWrapper);
  }

  private static String getAttributeText(Context context, int attribute, String defaultValue) {
    TypedValue outValue = new TypedValue();

    if (context.getTheme().resolveAttribute(attribute, outValue, true)) {
      CharSequence charSequence = outValue.coerceToString();
      if (charSequence != null) {
        return charSequence.toString();
      }
    }

    return defaultValue;
  }
}
