package com.example.contactimporter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class ContactAdapter extends BaseAdapter {
    public interface Listener {
        void onImport(Contact c);
        void onEdit(Contact c);
        void onDelete(Contact c);
    }

    private List<Contact> data = new ArrayList<>();
    private final Listener listener;

    public ContactAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setData(List<Contact> data) {
        this.data = data == null ? new ArrayList<>() : data;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Contact getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact, parent, false);
            h = new Holder();
            h.tvName = convertView.findViewById(R.id.tvName);
            h.tvPhone = convertView.findViewById(R.id.tvPhone);
            h.tvInfo = convertView.findViewById(R.id.tvInfo);
            h.btnImport = convertView.findViewById(R.id.btnOneImport);
            h.btnEdit = convertView.findViewById(R.id.btnEdit);
            h.btnDelete = convertView.findViewById(R.id.btnDelete);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        Contact c = getItem(position);
        h.tvName.setText(c.name);
        String displayPhone = (c.normalizedPhone == null || c.normalizedPhone.isEmpty()) ? c.phone : c.normalizedPhone;
        h.tvPhone.setText("手机号：" + safe(displayPhone));
        String info = "状态：" + Contact.statusText(c.status);
        if (c.importedAt != null && !c.importedAt.trim().isEmpty()) info += "｜导入时间：" + c.importedAt;
        if (c.sourceFile != null && !c.sourceFile.trim().isEmpty()) info += "｜来源：" + c.sourceFile;
        if (c.remark != null && !c.remark.trim().isEmpty()) info += "｜备注：" + c.remark;
        h.tvInfo.setText(info);

        h.btnImport.setEnabled(c.status == Contact.STATUS_PENDING);
        h.btnImport.setOnClickListener(v -> listener.onImport(c));
        h.btnEdit.setOnClickListener(v -> listener.onEdit(c));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(c));
        return convertView;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    static class Holder {
        TextView tvName, tvPhone, tvInfo;
        Button btnImport, btnEdit, btnDelete;
    }
}
