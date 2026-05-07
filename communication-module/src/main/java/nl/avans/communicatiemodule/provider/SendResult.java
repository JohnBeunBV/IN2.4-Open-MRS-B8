package nl.avans.communicatiemodule.provider;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SendResult {

    private final boolean success;
    private final String providerReference;
    private final String rawResponse;
    private final String errorMessage;

    public static SendResult success(String providerReference, String rawResponse) {
        return new SendResult(true, providerReference, rawResponse, null);
    }

    public static SendResult failure(String errorMessage) {
        return new SendResult(false, null, null, errorMessage);
    }

    public static SendResult failure(String errorMessage, String rawResponse) {
        return new SendResult(false, null, rawResponse, errorMessage);
    }
}
