import os
from PIL import Image

def generate_mipmaps(source_image_path, base_res_dir):
    # Standard sizes for ic_launcher
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
        "mipmap-anydpi-v26": None # For adaptive icons, though we'll just use legacy png for simplicity unless we create xml
    }
    
    img = Image.open(source_image_path)
    # Convert to RGBA for PNG
    img = img.convert("RGBA")
    
    for folder, size in sizes.items():
        if size is None:
            continue
        
        folder_path = os.path.join(base_res_dir, folder)
        os.makedirs(folder_path, exist_ok=True)
        
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        out_path = os.path.join(folder_path, "ic_launcher.png")
        resized.save(out_path, "PNG")
        print(f"Saved {out_path}")
        
        # Also create round icon
        out_path_round = os.path.join(folder_path, "ic_launcher_round.png")
        resized.save(out_path_round, "PNG")
        print(f"Saved {out_path_round}")

if __name__ == "__main__":
    source_img = r"C:\Users\A-ONE\.gemini\antigravity\brain\76dc2f7f-0110-4eea-933f-1529f9029a5b\media__1783015337567.jpg"
    app_res = r"d:\projects and VS\projects\android\StreamLinkWear_Phase1\app\src\main\res"
    wear_res = r"d:\projects and VS\projects\android\StreamLinkWear_Phase1\wear\src\main\res"
    
    generate_mipmaps(source_img, app_res)
    generate_mipmaps(source_img, wear_res)
    print("All mipmaps generated successfully.")
