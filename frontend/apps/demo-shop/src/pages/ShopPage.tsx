import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { getProducts } from "../api";
import { useCart } from "../cart";

export function ShopPage() {
  const cart = useCart();
  const { data: products, error, isPending } = useQuery({ queryKey: ["products"], queryFn: getProducts });

  if (isPending) return <p className="text-sm text-muted">Loading products…</p>;
  if (error || !products) return <Alert>The shop backend is not reachable. Start it with pnpm --filter @cutpay/demo-shop dev.</Alert>;

  return (
    <section aria-labelledby="products-heading">
      <h1 id="products-heading" className="mb-6 text-2xl font-semibold">
        Products
      </h1>
      <ul className="grid gap-4 sm:grid-cols-3">
        {products.map((product) => (
          <li key={product.id}>
            <Card className="flex h-full flex-col">
              <h2 className="font-semibold">{product.name}</h2>
              <p className="mt-1 flex-1 text-sm text-muted">{product.description}</p>
              <p className="mt-4 text-lg font-semibold">{formatMoney(product.unitAmount, product.currency)}</p>
              <Button className="mt-3" onClick={() => cart.add(product)} aria-label={`Add ${product.name} to cart`}>
                Add to cart
              </Button>
            </Card>
          </li>
        ))}
      </ul>
    </section>
  );
}
