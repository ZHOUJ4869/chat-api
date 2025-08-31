package com.jz.ai.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return of(200, "success", data);
    }

    public static <T> Result<T> error(String msg) {
        return of(500, msg, null);
    }
}
