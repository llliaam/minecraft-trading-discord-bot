package com.smp.marketplace.model;

public class OfferDto {
    public int id;
    public int listingId;
    public String priceItemKey;
    public int priceQuantity;
    public String priceText;
    public String message;
    public String status;
    public String buyerId;
    public ListingSummary listing;

    public static class ListingSummary {
        public int id;
        public String itemKey;
        public String itemLabel;
        public int quantity;
        public String priceText;
    }
}
