/*
 * Copyright (c) 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.api.client.sample.calendar.android;

import com.google.api.client.extensions.android2.AndroidHttp;
import com.google.api.client.googleapis.GoogleHeaders;
import com.google.api.client.googleapis.extensions.android2.auth.GoogleAccountManager;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.CalendarClient;
import com.google.api.services.calendar.CalendarRequestInitializer;
import com.google.api.services.calendar.CalendarUrl;
import com.google.api.services.calendar.model.CalendarEntry;
import com.google.api.services.calendar.model.CalendarFeed;
import com.google.api.services.calendar.model.EventEntry;
import com.google.api.services.calendar.model.EventFeed;
import com.google.common.collect.Lists;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sample for Google Calendar Data API using the Atom wire format. It shows how to authenticate, get
 * calendars, add a new calendar, update it, and delete it.
 * <p>
 * To enable logging of HTTP requests/responses, change {@link #LOGGING_LEVEL} to
 * {@link Level#CONFIG} or {@link Level#ALL} and run this command:
 * </p>
 * 
 * <pre>
adb shell setprop log.tag.HttpTransport DEBUG
 * </pre>
 * 
 * @author Yaniv Inbar
 */
@SuppressWarnings("all")
public final class CalendarSample extends ListActivity {

  /**
   * 
   */
  private static final String ALL_CALENDARS_URL =
      "https://www.google.com/calendar/feeds/default/allcalendars/full/";

  /**
   * 
   */
  private static final String OWN_CALENDAR_URL =
      "https://www.google.com/calendar/feeds/default/owncalendars/full/";

  /**
   * 
   */
  private static final String FUTURE_EVENTS_URL =
      "https://www.google.com/calendar/feeds/default/private/full?futureevents=true";

  /** Logging level for HTTP requests/responses. */
  private static Level LOGGING_LEVEL = Level.CONFIG;

  private static final String AUTH_TOKEN_TYPE = "cl";

  private static final String TAG = "CalendarSample";

  private static final int MENU_ADD = 0;

  private static final int MENU_ACCOUNTS = 1;

  private static final int CONTEXT_EDIT = 0;

  private static final int CONTEXT_DELETE = 1;

  private static final int CONTEXT_LIST_EVENTS = 2;

  private static final int REQUEST_AUTHENTICATE = 0;

  CalendarClient client;

  private final List<CalendarEntry> calendars = Lists.newArrayList();
  private final List<EventEntry> events = Lists.newArrayList();

  final HttpTransport transport = AndroidHttp.newCompatibleTransport();

  String accountName;

  static final String PREF = TAG;
  static final String PREF_ACCOUNT_NAME = "accountName";
  static final String PREF_AUTH_TOKEN = "authToken";
  static final String PREF_GSESSIONID = "gsessionid";
  GoogleAccountManager accountManager;
  SharedPreferences settings;
  CalendarAndroidRequestInitializer requestInitializer;

  public class CalendarAndroidRequestInitializer extends CalendarRequestInitializer {

    String authToken;

    public CalendarAndroidRequestInitializer() {
      super(transport);
      authToken = settings.getString(PREF_AUTH_TOKEN, null);
      setGsessionid(settings.getString(PREF_GSESSIONID, null));
    }

    @Override
    public void intercept(HttpRequest request) throws IOException {
      super.intercept(request);
      request.getHeaders().setAuthorization(GoogleHeaders.getGoogleLoginValue(authToken));
    }

    @Override
    public boolean handleResponse(HttpRequest request, HttpResponse response, boolean retrySupported)
        throws IOException {
      switch (response.getStatusCode()) {
        case 302:
          super.handleResponse(request, response, retrySupported);
          SharedPreferences.Editor editor = settings.edit();
          editor.putString(PREF_GSESSIONID, getGsessionid());
          editor.commit();
          return true;
        case 401:
          accountManager.invalidateAuthToken(authToken);
          authToken = null;
          SharedPreferences.Editor editor2 = settings.edit();
          editor2.remove(PREF_AUTH_TOKEN);
          editor2.commit();
          return false;
      }
      return false;
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Logger.getLogger("com.google.api.client").setLevel(LOGGING_LEVEL);
    accountManager = new GoogleAccountManager(this);
    settings = this.getSharedPreferences(PREF, 0);
    requestInitializer = new CalendarAndroidRequestInitializer();
    client = new CalendarClient(requestInitializer.createRequestFactory());
    client.setPrettyPrint(true);
    client.setApplicationName("Google-CalendarAndroidSample/1.0");
    getListView().setTextFilterEnabled(true);
    registerForContextMenu(getListView());
    gotAccount();
  }

  void setAuthToken(String authToken) {
    SharedPreferences.Editor editor = settings.edit();
    editor.putString(PREF_AUTH_TOKEN, authToken);
    editor.commit();
    requestInitializer.authToken = authToken;
  }

  void setAccountName(String accountName) {
    SharedPreferences.Editor editor = settings.edit();
    editor.putString(PREF_ACCOUNT_NAME, accountName);
    editor.remove(PREF_GSESSIONID);
    editor.commit();
    this.accountName = accountName;
    requestInitializer.setGsessionid(null);
  }

  private void gotAccount() {
    Account account = accountManager.getAccountByName(accountName);
    if (account != null) {
      // handle invalid token
      if (requestInitializer.authToken == null) {
        accountManager.manager.getAuthToken(account, AUTH_TOKEN_TYPE, true,
            new AccountManagerCallback<Bundle>() {

              public void run(AccountManagerFuture<Bundle> future) {
                try {
                  Bundle bundle = future.getResult();
                  if (bundle.containsKey(AccountManager.KEY_INTENT)) {
                    Intent intent = bundle.getParcelable(AccountManager.KEY_INTENT);
                    int flags = intent.getFlags();
                    flags &= ~Intent.FLAG_ACTIVITY_NEW_TASK;
                    intent.setFlags(flags);
                    startActivityForResult(intent, REQUEST_AUTHENTICATE);
                  } else if (bundle.containsKey(AccountManager.KEY_AUTHTOKEN)) {
                    setAuthToken(bundle.getString(AccountManager.KEY_AUTHTOKEN));
                    executeRefreshCalendars();
                  }
                } catch (Exception e) {
                  handleException(e);
                }
              }
            }, null);
      } else {
        executeRefreshCalendars();
      }
      return;
    }
    chooseAccount();
  }

  private void chooseAccount() {
    accountManager.manager.getAuthTokenByFeatures(GoogleAccountManager.ACCOUNT_TYPE,
        AUTH_TOKEN_TYPE, null, CalendarSample.this, null, null,
        new AccountManagerCallback<Bundle>() {

          public void run(AccountManagerFuture<Bundle> future) {
            Bundle bundle;
            try {
              bundle = future.getResult();
              setAccountName(bundle.getString(AccountManager.KEY_ACCOUNT_NAME));
              setAuthToken(bundle.getString(AccountManager.KEY_AUTHTOKEN));
              executeRefreshCalendars();
            } catch (OperationCanceledException e) {
              // user canceled
            } catch (AuthenticatorException e) {
              handleException(e);
            } catch (IOException e) {
              handleException(e);
            }
          }
        }, null);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    switch (requestCode) {
      case REQUEST_AUTHENTICATE:
        if (resultCode == RESULT_OK) {
          gotAccount();
        } else {
          chooseAccount();
        }
        break;
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, MENU_ADD, 0, getString(R.string.new_calendar));
    if (accountManager.getAccounts().length >= 2) {
      menu.add(0, MENU_ACCOUNTS, 0, getString(R.string.switch_account));
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case MENU_ADD:
        CalendarUrl url = new CalendarUrl(OWN_CALENDAR_URL);
        CalendarEntry calendar = new CalendarEntry();
        calendar.title = "Calendar " + new DateTime(new Date());
        try {
          client.calendarFeed().insert().execute(url, calendar);
        } catch (IOException e) {
          handleException(e);
        }
        executeRefreshCalendars();
        return true;
      case MENU_ACCOUNTS:
        chooseAccount();
        return true;
    }
    return false;
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);
    menu.add(0, CONTEXT_EDIT, 0, getString(R.string.update_title));
    menu.add(0, CONTEXT_DELETE, 0, getString(R.string.delete));
    menu.add(0, CONTEXT_LIST_EVENTS, 0, getString(R.string.events));
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
    CalendarEntry calendar = calendars.get((int) info.id);
    try {
      switch (item.getItemId()) {
        case CONTEXT_EDIT:
          CalendarEntry patchedCalendar = calendar.clone();
          patchedCalendar.title = calendar.title + " UPDATED " + new DateTime(new Date());
          client.executePatchRelativeToOriginal(calendar, patchedCalendar);
          executeRefreshCalendars();
          return true;
        case CONTEXT_DELETE:
          client.executeDelete(calendar);
          executeRefreshCalendars();
          return true;
        case CONTEXT_LIST_EVENTS:
          executeListEvents(calendar);
          return true;
        default:
          return super.onContextItemSelected(item);
      }
    } catch (IOException e) {
      handleException(e);
    }
    return false;
  }

  void executeRefreshCalendars() {
    String[] calendarNames;
    List<CalendarEntry> calendars = this.calendars;
    calendars.clear();
    try {
      CalendarUrl url = new CalendarUrl(ALL_CALENDARS_URL);
      // page through results
      while (true) {
        CalendarFeed feed = client.calendarFeed().list().execute(url);
        if (feed.calendars != null) {
          calendars.addAll(feed.calendars);
        }
        String nextLink = feed.getNextLink();
        if (nextLink == null) {
          break;
        }
      }
      int numCalendars = calendars.size();
      calendarNames = new String[numCalendars];
      for (int i = 0; i < numCalendars; i++) {
        calendarNames[i] = calendars.get(i).title;
      }
    } catch (IOException e) {
      handleException(e);
      calendarNames = new String[] {e.getMessage()};
      calendars.clear();
    }
    setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
        calendarNames));
  }

  void executeListEvents(CalendarEntry calendar) {
    String[] eventNames;
    calendar.getEventFeedLink();
    List<EventEntry> events = this.events;
    events.clear();
    try {
      CalendarUrl url = new CalendarUrl(FUTURE_EVENTS_URL);
      while (true) {
        EventFeed feed = client.eventFeed().list().execute(url);
        if (feed.events != null) {
          events.addAll(feed.events);
        }
        String nextLink = feed.getNextLink();
        if (nextLink == null) {
          break;
        }
      }
      int numCalendars = events.size();
      eventNames = new String[numCalendars];
      for (int i = 0; i < numCalendars; i++) {
        eventNames[i] = events.get(i).title;
      }
    } catch (IOException e) {
      handleException(e);
      eventNames = new String[] {e.getMessage()};
      events.clear();
    }
    setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, eventNames));
  }

  void handleException(Exception e) {
    e.printStackTrace();
    if (e instanceof HttpResponseException) {
      HttpResponse response = ((HttpResponseException) e).getResponse();
      int statusCode = response.getStatusCode();
      try {
        response.ignore();
      } catch (IOException e1) {
        e1.printStackTrace();
      }
      // TODO(yanivi): should only try this once to avoid infinite loop
      if (statusCode == 401) {
        gotAccount();
        return;
      }
      try {
        Log.e(TAG, response.parseAsString());
      } catch (IOException parseException) {
        parseException.printStackTrace();
      }
    }
    Log.e(TAG, e.getMessage(), e);
  }



}
