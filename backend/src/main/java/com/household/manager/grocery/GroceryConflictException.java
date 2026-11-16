package com.household.manager.grocery;

/** Ein Urteil passt nicht zum gespeicherten Zustand (z. B. „passt“ auf ein sicheres Urteil) – 409 im GroceryController. */
public class GroceryConflictException extends RuntimeException {

    public GroceryConflictException(String message) {
        super(message);
    }
}
