package com.tdk.news.model;

import java.time.LocalDateTime;

/**
 * Nhat ky mot lan thu thuat tin tu mot nguon (port tu CollectionLog o C#).
 * Duong luu sau moi nguon duoc thu thuat trong chuc nang "Cap nhat tin hom nay"
 * cua trang admin, ca khi thanh cong va khi that bai.
 * (Lop nay khong su dung Lombok de dam bao hoat dong tren tat ca moi truong build.)
 */
public class CollectionLog {
    private Long id;
    private Integer sourceId;
    private String sourceName;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private int itemsFound;
    private int itemsInserted;
    private String collectionMethod;
    private String note;
    private String error;

    public CollectionLog() {
    }

    public CollectionLog(Long id, Integer sourceId, String sourceName, LocalDateTime startedAt,
            LocalDateTime finishedAt, int itemsFound, int itemsInserted, String collectionMethod,
            String note, String error) {
        this.id = id;
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.itemsFound = itemsFound;
        this.itemsInserted = itemsInserted;
        this.collectionMethod = collectionMethod;
        this.note = note;
        this.error = error;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getSourceId() { return sourceId; }
    public void setSourceId(Integer sourceId) { this.sourceId = sourceId; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public int getItemsFound() { return itemsFound; }
    public void setItemsFound(int itemsFound) { this.itemsFound = itemsFound; }
    public int getItemsInserted() { return itemsInserted; }
    public void setItemsInserted(int itemsInserted) { this.itemsInserted = itemsInserted; }
    public String getCollectionMethod() { return collectionMethod; }
    public void setCollectionMethod(String collectionMethod) { this.collectionMethod = collectionMethod; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
