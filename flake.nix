{
  description = "Doge";

  nixConfig.bash-prompt-suffix = "dev$ ";

  inputs = {
    nixpkgs = {
      type = "github";
      owner = "NixOS";
      repo = "nixpkgs";
      ref = "nixos-22.11";
    };
  };

  outputs = { self, nixpkgs }:
    let pkgs = nixpkgs.legacyPackages.x86_64-linux; in {
      devShell.x86_64-linux = pkgs.mkShell {
        packages = [ pkgs.clang_14 pkgs.pkgconfig pkgs.openssl ];
        
        PKG_CONFIG_PATH = "${pkgs.openssl}/lib/pkgconfig/";
    };
  };
}
