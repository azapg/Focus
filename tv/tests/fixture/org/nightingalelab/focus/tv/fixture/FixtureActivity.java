package org.nightingalelab.focus.tv.fixture;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public final class FixtureActivity extends Activity {
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    setContentView(
        new View(this) {
          Paint p = new Paint(3);

          @Override
          protected void onDraw(Canvas c) {
            c.drawColor(0xff101216);
            p.setColor(0xff454b54);
            android.graphics.Path mountain = new android.graphics.Path();
            mountain.moveTo(0, getHeight());
            mountain.lineTo(getWidth() * .37f, getHeight() * .35f);
            mountain.lineTo(getWidth() * .67f, getHeight() * .85f);
            mountain.lineTo(getWidth(), getHeight() * .5f);
            mountain.lineTo(getWidth(), getHeight());
            c.drawPath(mountain, p);
            p.setColor(0xfffafafa);
            p.setTextSize(30 * getResources().getDisplayMetrics().density);
            c.drawText(
                "Focus TV Fixture",
                40 * getResources().getDisplayMetrics().density,
                65 * getResources().getDisplayMetrics().density,
                p);
          }
        });
  }
}
