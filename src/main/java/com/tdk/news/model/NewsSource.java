package com.tdk.news.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NewsSource {
    private Integer id;
    private String name;
    private String homeUrl;
    private String feedUrl;
    private Boolean enabled;
    private Boolean fullTextAllowed;
    private Integer sourceWeight;
}
