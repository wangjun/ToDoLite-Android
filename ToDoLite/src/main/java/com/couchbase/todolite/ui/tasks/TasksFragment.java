package com.couchbase.todolite.ui.tasks;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.couchbase.lite.LiveQuery;
import com.couchbase.lite.util.Log;
import com.couchbase.todolite.Application;
import com.couchbase.todolite.ListConflicts;
import com.couchbase.todolite.R;
import com.couchbase.todolite.document.Task;
import com.couchbase.todolite.helper.ImageHelper;
import com.couchbase.todolite.preferences.ToDoLitePreferences;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

public class TasksFragment extends Fragment {

    private static final int REQUEST_TAKE_PHOTO = 1;
    private static final int REQUEST_CHOOSE_PHOTO = 2;
    private static final int THUMBNAIL_SIZE_PX = 150;
    private static final String ARG_LIST_DOC_ID = "id";

    private ToDoLitePreferences preferences;

    private TasksAdapter mAdapter;
    private String mImagePathToBeAttached;
    private Bitmap mImageToBeAttached;
    private Document mCurrentTaskToAttachImage;

    private Database getDatabase() {
        Application application = (Application) getActivity().getApplication();
        return application.getDatabase();
    }

    public static TasksFragment newInstance(String id) {
        TasksFragment fragment = new TasksFragment();

        Bundle args = new Bundle();
        args.putString(ARG_LIST_DOC_ID, id);
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != getActivity().RESULT_OK) {
            if (mCurrentTaskToAttachImage != null) {
                mCurrentTaskToAttachImage = null;
            }
            return;
        }

        Bitmap thumbnail = null;
        if (requestCode == REQUEST_TAKE_PHOTO) {
            mImageToBeAttached = BitmapFactory.decodeFile(mImagePathToBeAttached);
            if (mCurrentTaskToAttachImage == null) {
                thumbnail = ImageHelper.thumbmailFromFile(mImagePathToBeAttached,
                        THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX);
            }

            // Delete the temporary image file
            File file = new File(mImagePathToBeAttached);
            file.delete();
            mImagePathToBeAttached = null;
        } else if (requestCode == REQUEST_CHOOSE_PHOTO) {
            try {
                Uri uri = data.getData();
                mImageToBeAttached = MediaStore.Images.Media.getBitmap(
                        getActivity().getContentResolver(), uri);
                if (mCurrentTaskToAttachImage == null) {
                    AssetFileDescriptor descriptor =
                            getActivity().getContentResolver().openAssetFileDescriptor(uri, "r");
                    thumbnail = ImageHelper.thumbmailFromDescriptor(descriptor.getFileDescriptor(),
                            THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX);
                }
            } catch (IOException e) {
                Log.e(Application.TAG, "Cannot get a selected photo from the gallery.", e);
            }
        }

        if (mImageToBeAttached != null) {
            if (mCurrentTaskToAttachImage != null) {
                try {
                    Task.attachImage(mCurrentTaskToAttachImage, mImageToBeAttached);
                    mImageToBeAttached = null;
                } catch (CouchbaseLiteException e) {
                    Log.e(Application.TAG, "Cannot attach an image to a task.", e);
                }
            } else { // Attach an image for a new task
                ImageView imageView = (ImageView) getActivity().findViewById(R.id.image);
                imageView.setImageBitmap(thumbnail);
            }
        }

        // Ensure resetting the task to attach an image
        if (mCurrentTaskToAttachImage != null) {
            mCurrentTaskToAttachImage = null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        this.preferences = new ToDoLitePreferences(getActivity());

        final ListView listView = (ListView) inflater.inflate(R.layout.fragment_main, container, false);

        final String listId = getArguments().getString(ARG_LIST_DOC_ID);

        ViewGroup header = (ViewGroup) inflater.inflate(R.layout.view_task_create, listView, false);

        final ImageView imageView = (ImageView) header.findViewById(R.id.image);
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attachImage(null);
            }
        });

        final EditText text = (EditText) header.findViewById(R.id.text);
        text.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int i, KeyEvent keyEvent) {
                if ((keyEvent.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    String inputText = text.getText().toString();
                    if (inputText.length() > 0) {
                        try {
                            Task.createTask(getDatabase(), inputText, mImageToBeAttached, listId);
                        } catch (CouchbaseLiteException e) {
                            Log.e(Application.TAG, "Cannot create new task", e);
                        }
                    }

                    // Reset text and current selected photo if available.
                    text.setText("");
                    deleteCurrentPhoto();

                    return true;
                }
                return false;
            }
        });

        listView.addHeaderView(header);

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position,
                                           long id) {
                PopupMenu popup = new PopupMenu(getActivity(), view);
                popup.getMenu().add(getResources().getString(R.string.action_update));
                popup.getMenu().add(getResources().getString(R.string.action_delete));
                popup.getMenu().add(getResources().getString(R.string.action_show_document));

                /*
                Only show the Resolve conflict tab if there are conflicting revisions
                 */
                Document task = (Document) mAdapter.getItem(position - 1);
                try {
                    if (task.getConflictingRevisions().size() > 1) {
                        popup.getMenu().add("Resolve Conflict");
                    }
                } catch (CouchbaseLiteException e) {
                    e.printStackTrace();
                }

                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getTitle().equals(getResources().getString(R.string.action_delete))) {
                            deleteTask(position - 1);
                        } else if (item.getTitle().equals(getResources().getString(R.string.action_show_document))) {
                            Document task = (Document) mAdapter.getItem(position - 1);
                            System.out.println("Doc id: " + task.getId());
                            Map<String, Object> documentMap = task.getProperties();
                            ObjectMapper objectMapper = new ObjectMapper();
                            try {
                                String documentString = objectMapper.writeValueAsString(documentMap);
                                Toast.makeText(getActivity(), documentString, Toast.LENGTH_LONG).show();
                            } catch (IOException e) {
                                Toast.makeText(getActivity(), "Error showing document", Toast.LENGTH_LONG).show();
                                Log.d(Application.TAG, "Error showing document", e);
                            }

                        } else if (item.getTitle().equals(getResources().getString(R.string.action_update))) {
                            AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
                            alert.setTitle(getResources().getString(R.string.title_dialog_update));

                            final EditText input = new EditText(getActivity());
                            input.setMaxLines(1);
                            input.setSingleLine(true);
                            Document task = (Document) mAdapter.getItem(position - 1);
                            String text = (String) task.getProperty("title");
                            input.setText(text);
                            alert.setView(input);

                            alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    Document task = (Document) mAdapter.getItem(position - 1);

                                    /*
                                    Get the current time to update the update_at property with the
                                    latest time. This property is displayed on the conflict resolution
                                    screen.
                                     */
                                    SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                                    Calendar calendar = GregorianCalendar.getInstance();
                                    String currentTimeString = dateFormatter.format(calendar.getTime());

                                    Map<String, Object> updatedProperties = new HashMap<String, Object>();
                                    updatedProperties.putAll(task.getProperties());
                                    updatedProperties.put("title", input.getText().toString());
                                    updatedProperties.put("updated_at", currentTimeString);

                                    /*
                                    Update the task document with the user id,
                                    will be shown on the conflict resolution
                                    screen.
                                     */
                                    String user_id = preferences.getCurrentUserId();
                                    updatedProperties.put("user_id", user_id);

                                    try {
                                        task.putProperties(updatedProperties);
                                    } catch (CouchbaseLiteException e) {
                                        e.printStackTrace();
                                    }
                                }
                            });

                            alert.show();

                        } else if (item.getTitle().equals("Resolve Conflict")) {

                            /*
                            Show the list of conflicting revisions, pass in the id of the current
                            revision as an extra.
                             */
                            Document task = (Document) mAdapter.getItem(position - 1);
                            Intent i = new Intent(getActivity(), ListConflicts.class);
                            i.putExtra("DOC_ID", task.getId());
                            startActivityForResult(i, 0);

                        }
                        return true;
                    }
                });

                popup.show();
                return true;
            }
        });

        LiveQuery query = Task.getQuery(getDatabase(), listId).toLiveQuery();
        mAdapter = new TasksAdapter(getActivity(), query);
        listView.setAdapter(mAdapter);

        /*
        Database change listener is called when new non current revisions
        are inserted.
         */
        Application application = (Application) getActivity().getApplication();
        application.getDatabase().addChangeListener(new Database.ChangeListener() {
            @Override
            public void changed(Database.ChangeEvent event) {
                if (event.isExternal()) {
                    mAdapter.updateQueryToShowConflictingRevisions(event);
                }
            }
        });

        return listView;
    }

    private void attachImage(final Document task) {
        CharSequence[] items;
        if (mImageToBeAttached != null)
            items = new CharSequence[] { "Take photo", "Choose photo", "Delete photo" };
        else
            items = new CharSequence[] { "Take photo", "Choose photo" };

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Add picture");
        builder.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int item) {
                if (item == 0) {
                    mCurrentTaskToAttachImage = task;
                    dispatchTakePhotoIntent();
                } else if (item == 1) {
                    mCurrentTaskToAttachImage = task;
                    dispatchChoosePhotoIntent();
                } else {
                    deleteCurrentPhoto();
                }
            }
        });
        builder.show();
    }

    private void deleteCurrentPhoto() {
        if (mImageToBeAttached != null) {
            mImageToBeAttached.recycle();
            mImageToBeAttached = null;

            ViewGroup createTaskPanel = (ViewGroup) getActivity().findViewById(
                    R.id.create_task);
            ImageView imageView = (ImageView) createTaskPanel.findViewById(R.id.image);
            imageView.setImageDrawable(getResources().getDrawable(R.drawable.ic_camera));
        }
    }

    private void deleteTask(int position) {
        Document task = (Document) mAdapter.getItem(position);
        try {
            Task.deleteTask(task);
        } catch (CouchbaseLiteException e) {
            Log.e(Application.TAG, "Cannot delete a task", e);
        }
    }

    private void dispatchTakePhotoIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getActivity().getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException e) {
                Log.e(Application.TAG, "Cannot create a temp image file", e);
            }

            if (photoFile != null) {
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(photoFile));
                startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO);
            }
        }
    }

    private void dispatchChoosePhotoIntent() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "Select File"),
                REQUEST_CHOOSE_PHOTO);
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "TODO_LITE_" + timeStamp + "_";
        File storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(fileName, ".jpg", storageDir);
        mImagePathToBeAttached = image.getAbsolutePath();
        return image;
    }

}