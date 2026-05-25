from PIL import Image, ImageDraw, ImageChops, ImageOps
import os
import sys

def process_folder(input_folder, output_folder, template_path):
    if not os.path.exists(input_folder):
        print(f"Error: Input folder '{input_folder}' does not exist.")
        return
    if not os.path.exists(template_path):
        print(f"Error: Template image '{template_path}' does not exist.")
        return
        
    os.makedirs(output_folder, exist_ok=True)
    
    # 1. Prepare the frame from the template
    frame = Image.open(template_path).convert('RGBA')
    
    # Hollow out the center of the frame (keep the border and drop shadow)
    erase_mask = Image.new('L', frame.size, 255)
    draw = ImageDraw.Draw(erase_mask)
    # The inner area to erase, leaving the 1-px stroke and shadow intact
    draw.rounded_rectangle((21, 11, 458, 209), radius=8, fill=0)
    
    original_alpha = frame.split()[3]
    new_alpha = ImageChops.multiply(original_alpha, erase_mask)
    frame.putalpha(new_alpha)
    
    valid_extensions = {'.png', '.jpg', '.jpeg', '.webp'}
    processed_count = 0
    
    for filename in os.listdir(input_folder):
        name, ext = os.path.splitext(filename)
        if ext.lower() not in valid_extensions:
            continue
            
        input_path = os.path.join(input_folder, filename)
        output_path = os.path.join(output_folder, f"{name}.png")
        
        try:
            target = Image.open(input_path).convert('RGBA')
            
            # Resize target to fill the canvas, cropping excess if necessary
            target = ImageOps.fit(target, frame.size, Image.Resampling.LANCZOS)
            
            # Mask the target so it doesn't spill outside the card's rounded corners
            target_mask = Image.new('L', frame.size, 0)
            target_draw = ImageDraw.Draw(target_mask)
            target_draw.rounded_rectangle((12, 2, 467, 218), radius=10, fill=255)
            target.putalpha(target_mask)
            
            # Composite the target with the frame on top
            final = Image.new('RGBA', frame.size, (0, 0, 0, 0))
            final.alpha_composite(target)
            final.alpha_composite(frame)
            
            final.save(output_path)
            print(f"Successfully created: {output_path}")
            processed_count += 1
            
        except Exception as e:
            print(f"Error processing {filename}: {e}")
            
    print(f"\\nDone! Processed {processed_count} images.")

if __name__ == "__main__":
    default_in = r"e:\projects\lemuroid\Lemuroid\logos_extracted\raw"
    default_out = r"e:\projects\lemuroid\Lemuroid\logos_extracted"
    default_template = r"e:\projects\lemuroid\Lemuroid\logos_extracted\atari2600.png"
    
    input_folder = sys.argv[1] if len(sys.argv) > 1 else default_in
    output_folder = sys.argv[2] if len(sys.argv) > 2 else default_out
    template_path = sys.argv[3] if len(sys.argv) > 3 else default_template
    
    print(f"Reading from: {input_folder}")
    print(f"Saving to: {output_folder}\\n")
    
    process_folder(input_folder, output_folder, template_path)

