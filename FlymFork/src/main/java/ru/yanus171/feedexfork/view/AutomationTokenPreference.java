package ru.yanus171.feedexfork.view;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.utils.AutomationAuth;
import ru.yanus171.feedexfork.utils.Theme;
import ru.yanus171.feedexfork.utils.UiUtils;

/**
 * HandyRss: the 保存復元 automation-token row, in the Export/Import section right under the master
 * switch. Shows the token abbreviated, copies the full value to the clipboard on tap, and carries
 * a Regenerate action that warns any pasted copy must be updated.
 */
public class AutomationTokenPreference extends Preference {

    public AutomationTokenPreference(Context context, AttributeSet attrs) {
        super( context, attrs );
        setPersistent( false );
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView( view );

        // The token itself, in full, on the line under the title — 白い熊 reads it off the screen
        // (monospace so the hex stays legible when it wraps); tapping the row still copies it.
        final TextView value = view.findViewById( R.id.automation_token_value );
        if ( value != null ) {
            value.setText( AutomationAuth.getToken() );
            value.setTypeface( Typeface.MONOSPACE );
        }

        final Button regenerate = view.findViewById( R.id.automation_regenerate );
        if ( regenerate != null ) {
            final int fg = Theme.GetChromeFgInt(), bg = Theme.GetChromeBgInt();
            final GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius( 100 * getContext().getResources().getDisplayMetrics().density );
            pill.setColor( bg );
            pill.setStroke( UiUtils.dpToPixel( 2 ), fg );
            regenerate.setBackground( pill );
            regenerate.setTextColor( fg );
            regenerate.setOnClickListener( v -> confirmRegenerate() );
        }
    }

    /** Tapping the row copies the whole token — that is what gets pasted into 自由作業盤. */
    @Override
    protected void onClick() {
        final ClipboardManager clipboard =
                (ClipboardManager) getContext().getSystemService( Context.CLIPBOARD_SERVICE );
        if ( clipboard == null )
            return;
        clipboard.setPrimaryClip( ClipData.newPlainText(
                getContext().getString( R.string.automation_token_title ), AutomationAuth.getToken() ) );
        UiUtils.styledToast( getContext(), R.string.automation_token_copied, Toast.LENGTH_SHORT );
    }

    private void confirmRegenerate() {
        final AlertDialog dialog = new AlertDialog.Builder( getContext() )
                .setTitle( R.string.automation_token_regenerate )
                .setMessage( R.string.automation_token_regenerate_warn )
                .setNegativeButton( android.R.string.cancel, null )
                .setPositiveButton( android.R.string.ok, (d, which) -> {
                    AutomationAuth.regenerate();
                    notifyChanged();
                    UiUtils.styledToast( getContext(), R.string.automation_token_regenerated, Toast.LENGTH_SHORT );
                } )
                .create();
        dialog.show();
        Theme.TintDialog( dialog ); // must follow show() — the buttons do not exist before it
    }
}
