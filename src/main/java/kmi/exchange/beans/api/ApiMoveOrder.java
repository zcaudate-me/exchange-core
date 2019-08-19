package kmi.exchange.beans.api;


import lombok.Builder;

@Builder
public final class ApiMoveOrder extends ApiCommand {

    public long id;

    public long newPrice;

    public long uid;
    public int symbol;

    @Override
    public String toString() {
        return "[MOVE " + id + " " + newPrice + "]";
    }
}
